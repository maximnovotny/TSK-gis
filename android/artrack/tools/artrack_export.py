#!/usr/bin/env python3
"""
Turn an ARTrack session into photogrammetry input: COLMAP poses + a WGS84 list.

An ARTrack session folder holds frames/ with full resolution JPEGs, frames.csv
with the camera pose of every JPEG, pose.csv with the whole trajectory, gps.csv
and meta.json. Optionally also video.mp4, which ARCore records at low resolution.

Two modes, picked automatically:

  photos (default when frames.csv has rows)
      uses the JPEGs in frames/ as they are, no re-encoding, no ffmpeg
  video (--from-video, or when there are no stills)
      cuts every Nth frame out of video.mp4 with ffmpeg and takes the pose from
      the matching row of pose.csv

Outputs into <session>/export:
      colmap/cameras.txt, colmap/images.txt, colmap/points3D.txt
      frames_geo.csv        per image: WGS84 lat/lon/alt + local AR position
      realityscan/          trajectory.csv (camera position priors in a projected
                            or local CRS), exif_gps.args for exiftool, optional
                            XMP sidecars, and jak-importovat.txt with the steps
      images/               only in video mode: the extracted frames

Standard library only; ffmpeg is needed for video mode.

    python3 artrack_export.py /path/to/20261127_143512
    python3 artrack_export.py /path/to/20261127_143512 --step 2
    python3 artrack_export.py /path/to/20261127_143512 --from-video --step 10
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import subprocess
import sys


# --------------------------------------------------------------------- reading

def read_poses(path):
    rows = []
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            rows.append(row)
    return rows


MAX_GPS_AGE_MS = 5000.0


def read_fixes(path):
    """GPS fixes, without the stale ones: a fix older than 5 s says where the
    phone was, not where it is. Older recordings have no age_ms column."""
    fixes = []
    skipped = 0
    if not os.path.exists(path):
        return fixes
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            try:
                age = float(row.get("age_ms") or 0.0)
                if age > MAX_GPS_AGE_MS:
                    skipped += 1
                    continue
                fixes.append((
                    float(row["t_rel_s_approx"]),
                    float(row["lat"]),
                    float(row["lon"]),
                    float(row["alt_m"]),
                    float(row["h_acc_m"]),
                ))
            except (KeyError, ValueError):
                continue
    if skipped:
        print(f"note: {skipped} GPS fix(es) older than {MAX_GPS_AGE_MS / 1000:.0f} s "
              f"left out of the fit")
    fixes.sort(key=lambda f: f[0])
    return fixes


def interpolate_fix(fixes, t):
    """Linear interpolation of a GPS fix at video time t; clamps at the ends."""
    if not fixes:
        return None
    if t <= fixes[0][0]:
        return fixes[0][1:]
    if t >= fixes[-1][0]:
        return fixes[-1][1:]
    lo = 0
    hi = len(fixes) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if fixes[mid][0] <= t:
            lo = mid
        else:
            hi = mid
    t0, *a = fixes[lo]
    t1, *b = fixes[hi]
    if t1 == t0:
        return a
    w = (t - t0) / (t1 - t0)
    return [x + (y - x) * w for x, y in zip(a, b)]


# ----------------------------------------------------------------------- maths

def quat_to_matrix(x, y, z, w):
    n = math.sqrt(x * x + y * y + z * z + w * w)
    if n == 0.0:
        raise ValueError("zero quaternion")
    x, y, z, w = x / n, y / n, z / n, w / n
    return [
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ]


def matrix_to_quat(m):
    """Rotation matrix -> (qw, qx, qy, qz)."""
    tr = m[0][0] + m[1][1] + m[2][2]
    if tr > 0:
        s = math.sqrt(tr + 1.0) * 2
        qw = 0.25 * s
        qx = (m[2][1] - m[1][2]) / s
        qy = (m[0][2] - m[2][0]) / s
        qz = (m[1][0] - m[0][1]) / s
    elif m[0][0] > m[1][1] and m[0][0] > m[2][2]:
        s = math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2
        qw = (m[2][1] - m[1][2]) / s
        qx = 0.25 * s
        qy = (m[0][1] + m[1][0]) / s
        qz = (m[0][2] + m[2][0]) / s
    elif m[1][1] > m[2][2]:
        s = math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2
        qw = (m[0][2] - m[2][0]) / s
        qx = (m[0][1] + m[1][0]) / s
        qy = 0.25 * s
        qz = (m[1][2] + m[2][1]) / s
    else:
        s = math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2
        qw = (m[1][0] - m[0][1]) / s
        qx = (m[0][2] + m[2][0]) / s
        qy = (m[1][2] + m[2][1]) / s
        qz = 0.25 * s
    return qw, qx, qy, qz


def arcore_to_colmap(tx, ty, tz, qx, qy, qz, qw):
    """
    ARCore: camera-to-world, camera looks down -Z with +Y up (OpenGL style).
    COLMAP: world-to-camera, camera looks down +Z with +Y down (OpenCV style).
    """
    r = quat_to_matrix(qx, qy, qz, qw)                 # camera(GL) -> world
    # flip Y and Z of the camera axes: R_cv = R_gl * diag(1, -1, -1)
    r_cv = [[r[i][0], -r[i][1], -r[i][2]] for i in range(3)]
    # invert: world -> camera
    r_cw = [[r_cv[j][i] for j in range(3)] for i in range(3)]
    t = (tx, ty, tz)
    t_cw = [-sum(r_cw[i][j] * t[j] for j in range(3)) for i in range(3)]
    qw_c, qx_c, qy_c, qz_c = matrix_to_quat(r_cw)
    return (qw_c, qx_c, qy_c, qz_c), t_cw


# ------------------------------------------------------------------ geodesy

WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563


def utm_zone_for(lon):
    return int(math.floor((lon + 180.0) / 6.0)) + 1


def latlon_to_utm(lat, lon, zone):
    """
    Forward UTM (WGS84) after Snyder / USGS Professional Paper 1395.
    Returns (easting, northing) in metres.
    """
    k0 = 0.9996
    e2 = 2 * WGS84_F - WGS84_F * WGS84_F
    ep2 = e2 / (1 - e2)
    lat_r = math.radians(lat)
    lon_r = math.radians(lon)
    lon0 = math.radians((zone - 1) * 6 - 180 + 3)

    sin_lat = math.sin(lat_r)
    cos_lat = math.cos(lat_r)
    tan_lat = math.tan(lat_r)

    n = WGS84_A / math.sqrt(1 - e2 * sin_lat * sin_lat)
    t = tan_lat * tan_lat
    c = ep2 * cos_lat * cos_lat
    a = (lon_r - lon0) * cos_lat

    m = WGS84_A * (
        (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * lat_r
        - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * lat_r)
        + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * lat_r)
        - (35 * e2 ** 3 / 3072) * math.sin(6 * lat_r)
    )

    easting = 500000.0 + k0 * n * (
        a
        + (1 - t + c) * a ** 3 / 6
        + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a ** 5 / 120
    )
    northing = k0 * (
        m
        + n * tan_lat * (
            a * a / 2
            + (5 - t + 9 * c + 4 * c * c) * a ** 4 / 24
            + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a ** 6 / 720
        )
    )
    if lat < 0:
        northing += 10000000.0
    return easting, northing


def ar_horizontal(tx, tz):
    """
    ARCore world is Y-up and right handed, so (x, -z) is the horizontal pair that
    keeps the handedness of (east, north, up). Using (x, z) would mirror the track.
    """
    return tx, -tz


def track_extent(points):
    """Largest distance between two points of the track, in metres."""
    worst = 0.0
    for i in range(len(points)):
        for j in range(i + 1, len(points)):
            d = math.hypot(points[i][0] - points[j][0], points[i][1] - points[j][1])
            if d > worst:
                worst = d
    return worst


def fit_heading(ar_xy, enu_xy):
    """
    Least squares rotation about the vertical axis plus translation, with the scale
    locked at 1 (AR is already metric; GPS noise would only corrupt a scale fit).
    Returns (theta_rad, e0, n0, rms_m).
    """
    n = len(ar_xy)
    ax = sum(p[0] for p in ar_xy) / n
    ay = sum(p[1] for p in ar_xy) / n
    ex = sum(p[0] for p in enu_xy) / n
    ey = sum(p[1] for p in enu_xy) / n
    num = 0.0
    den = 0.0
    for (x, y), (e, nn) in zip(ar_xy, enu_xy):
        dx, dy = x - ax, y - ay
        de, dn = e - ex, nn - ey
        num += dx * dn - dy * de      # cross product
        den += dx * de + dy * dn      # dot product
    theta = math.atan2(num, den)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    e0 = ex - (cos_t * ax - sin_t * ay)
    n0 = ey - (sin_t * ax + cos_t * ay)
    sq = 0.0
    for (x, y), (e, nn) in zip(ar_xy, enu_xy):
        pe = e0 + cos_t * x - sin_t * y
        pn = n0 + sin_t * x + cos_t * y
        sq += (pe - e) ** 2 + (pn - nn) ** 2
    return theta, e0, n0, math.sqrt(sq / n)


def interpolate_position(rows, t):
    """AR position at time t, linearly interpolated from CSV rows with t_rel_s."""
    if not rows:
        return None
    times = [float(r["t_rel_s"]) for r in rows]
    if t <= times[0]:
        r = rows[0]
        return float(r["tx"]), float(r["ty"]), float(r["tz"])
    if t >= times[-1]:
        r = rows[-1]
        return float(r["tx"]), float(r["ty"]), float(r["tz"])
    lo, hi = 0, len(rows) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if times[mid] <= t:
            lo = mid
        else:
            hi = mid
    w = (t - times[lo]) / (times[hi] - times[lo]) if times[hi] != times[lo] else 0.0
    a, b = rows[lo], rows[hi]
    return tuple(
        float(a[k]) + (float(b[k]) - float(a[k])) * w for k in ("tx", "ty", "tz")
    )


# ------------------------------------------------------------------ extraction

def extract_frames(video, out_dir, step, quality, limit):
    os.makedirs(out_dir, exist_ok=True)
    for old in os.listdir(out_dir):
        if old.startswith("frame_") and old.endswith(".jpg"):
            os.remove(os.path.join(out_dir, old))
    select = "1" if step <= 1 else f"not(mod(n\\,{step}))"
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", video,
        "-vf", f"select='{select}'", "-vsync", "0", "-q:v", str(quality),
    ]
    if limit:
        cmd += ["-frames:v", str(limit)]
    cmd.append(os.path.join(out_dir, "frame_%06d.jpg"))
    subprocess.run(cmd, check=True)
    return sorted(f for f in os.listdir(out_dir) if f.endswith(".jpg"))


# -------------------------------------------------------------- realityscan

def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def transpose(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


# AR (x, y, z) -> local project axes (x, -z, y), matching project() below.
LOCAL_AXES = [[1.0, 0.0, 0.0], [0.0, 0.0, -1.0], [0.0, 1.0, 0.0]]


def ar_to_enu_matrix(theta):
    """
    Rotation that takes an ARCore world vector (x, y, z) to (east, north, up),
    where theta is the azimuth of the AR +X axis measured from east.
    """
    c, s = math.cos(theta), math.sin(theta)
    return [[c, 0.0, s],
            [s, 0.0, -c],
            [0.0, 1.0, 0.0]]


def write_realityscan(out, pairs, fixes, traj_rows, args, meta):
    """
    Writes a RealityScan trajectory (camera position priors), an exiftool argfile
    for the EXIF route, optional XMP sidecars and a step-by-step instruction file.
    """
    rs_dir = os.path.join(out, "realityscan")
    os.makedirs(rs_dir, exist_ok=True)

    local = (args.epsg or "auto").lower() == "local"
    theta = e0 = n0 = rms = None
    extent = 0.0
    zone = None
    epsg = None
    alt_offset = 0.0
    used_fixes = 0

    if not local:
        good = [f for f in fixes if f[4] >= 0 and f[4] <= args.max_gps_error]
        if len(good) < 2 and fixes:
            good = fixes
        if len(good) < 2:
            print("note: fewer than two usable GPS fixes - writing a local coordinate "
                  "system instead of a projected one")
            local = True
        else:
            mean_lon = sum(f[2] for f in good) / len(good)
            mean_lat = sum(f[1] for f in good) / len(good)
            if args.epsg and args.epsg.lower() != "auto":
                epsg = int(args.epsg)
                zone = epsg - (32700 if epsg >= 32700 else 32600)
            else:
                zone = utm_zone_for(mean_lon)
                epsg = (32600 if mean_lat >= 0 else 32700) + zone
            ar_xy, enu_xy, alts = [], [], []
            for t_rel, lat, lon, alt, acc in good:
                pos = interpolate_position(traj_rows, t_rel)
                if pos is None:
                    continue
                ar_xy.append(ar_horizontal(pos[0], pos[2]))
                enu_xy.append(latlon_to_utm(lat, lon, zone))
                alts.append(alt - pos[1])
            used_fixes = len(ar_xy)
            if used_fixes < 2:
                local = True
            else:
                if args.heading is not None:
                    theta = math.radians(args.heading)
                    cx = sum(p[0] for p in ar_xy) / used_fixes
                    cy = sum(p[1] for p in ar_xy) / used_fixes
                    ce = sum(p[0] for p in enu_xy) / used_fixes
                    cn = sum(p[1] for p in enu_xy) / used_fixes
                    e0 = ce - (math.cos(theta) * cx - math.sin(theta) * cy)
                    n0 = cn - (math.sin(theta) * cx + math.cos(theta) * cy)
                    rms = None
                else:
                    theta, e0, n0, rms = fit_heading(ar_xy, enu_xy)
                alt_offset = sum(alts) / len(alts)
                extent = track_extent(ar_xy)

    def project(row):
        tx, ty, tz = float(row["tx"]), float(row["ty"]), float(row["tz"])
        if local:
            x, y = ar_horizontal(tx, tz)
            return x, y, ty
        x, y = ar_horizontal(tx, tz)
        c, s = math.cos(theta), math.sin(theta)
        return (e0 + c * x - s * y, n0 + s * x + c * y, ty + alt_offset)

    rows = [(name, project(row)) for name, row in pairs]

    csv_path = os.path.join(rs_dir, "trajectory.csv")
    with open(csv_path, "w", newline="") as fh:
        writer = csv.writer(fh)
        for name, (x, y, z) in rows:
            writer.writerow([name, f"{x:.4f}", f"{y:.4f}", f"{z:.4f}"])
    with open(os.path.join(rs_dir, "trajectory_space.txt"), "w") as fh:
        for name, (x, y, z) in rows:
            fh.write(f"{name} {x:.4f} {y:.4f} {z:.4f}\n")

    # EXIF route: stamp the WGS84 position into the JPEGs themselves.
    args_path = os.path.join(rs_dir, "exif_gps.args")
    stamped = 0
    with open(args_path, "w") as fh:
        for name, row in pairs:
            lat, lon, alt = row.get("lat"), row.get("lon"), row.get("alt_m")
            if not lat:
                fix = interpolate_fix(fixes, float(row["t_rel_s"]))
                if not fix:
                    continue
                lat, lon, alt = f"{fix[0]:.8f}", f"{fix[1]:.8f}", f"{fix[2]:.3f}"
            fh.write(f"-GPSLatitude={abs(float(lat)):.8f}\n")
            fh.write(f"-GPSLatitudeRef={'N' if float(lat) >= 0 else 'S'}\n")
            fh.write(f"-GPSLongitude={abs(float(lon)):.8f}\n")
            fh.write(f"-GPSLongitudeRef={'E' if float(lon) >= 0 else 'W'}\n")
            fh.write(f"-GPSAltitude={float(alt):.3f}\n")
            fh.write("-GPSAltitudeRef=above\n")
            fh.write(f"{name}\n")
            fh.write("-execute\n")
            stamped += 1
        fh.write("-common_args\n-overwrite_original\n")

    xmp_count = 0
    if args.xmp:
        xmp_dir = os.path.join(rs_dir, "xmp")
        os.makedirs(xmp_dir, exist_ok=True)
        m_enu = None if local else ar_to_enu_matrix(theta)
        for (name, row), (_, pos) in zip(pairs, rows):
            r_gl = quat_to_matrix(float(row["qx"]), float(row["qy"]),
                                  float(row["qz"]), float(row["qw"]))
            r_cv = [[r_gl[i][0], -r_gl[i][1], -r_gl[i][2]] for i in range(3)]
            # same AR -> project axis mapping that project() applies to the positions
            r_cv = matmul(m_enu if m_enu is not None else LOCAL_AXES, r_cv)
            r_cw = transpose(r_cv)
            if args.xmp_transpose:
                r_cw = transpose(r_cw)
            width = int(row.get("width") or 0) or int(
                (meta.get("photo_intrinsics") or {}).get("width") or 0)
            height = int(row.get("height") or 0) or int(
                (meta.get("photo_intrinsics") or {}).get("height") or 0)
            fx, cx, cy = float(row["fx"]), float(row["cx"]), float(row["cy"])
            f35 = fx / width * 36.0 if width else 0.0
            ppu = (cx - width / 2.0) / width if width else 0.0
            ppv = (cy - height / 2.0) / width if width else 0.0
            flat = " ".join(f"{v:.9f}" for r in r_cw for v in r)
            stem = os.path.splitext(name)[0]
            with open(os.path.join(xmp_dir, stem + ".xmp"), "w") as fh:
                fh.write(f"""<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description xcr:Version="3" xcr:PosePrior="initial" xcr:Coordinates="absolute"
      xcr:DistortionModel="brown3" xcr:FocalLength35mm="{f35:.6f}"
      xcr:Skew="0" xcr:AspectRatio="1"
      xcr:PrincipalPointU="{ppu:.6f}" xcr:PrincipalPointV="{ppv:.6f}"
      xcr:CalibrationPrior="initial" xcr:CalibrationGroup="-1" xcr:DistortionGroup="-1"
      xcr:InTexturing="1" xcr:InMeshing="1"
      xmlns:xcr="http://www.capturingreality.com/ns/xcr/1.1#">
      <xcr:Rotation>{flat}</xcr:Rotation>
      <xcr:Position>{pos[0]:.6f} {pos[1]:.6f} {pos[2]:.6f}</xcr:Position>
      <xcr:DistortionCoeficients>0 0 0 0 0 0</xcr:DistortionCoeficients>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
""")
            xmp_count += 1

    crs = "lokální kartézský systém (metry)" if local else f"EPSG:{epsg} (UTM {zone}N)"
    report = []
    report.append(f"souřadnicový systém : {crs}")
    if not local:
        report.append(f"GPS fixů pro fit    : {used_fixes}")
        report.append(f"azimut AR osy +X    : {math.degrees(theta) % 360.0:.2f}° od východu"
                      + (" (zadaný ručně)" if args.heading is not None else ""))
        report.append("odchylka fitu (RMS) : "
                      + ("neznámá (heading zadán ručně)" if rms is None else f"{rms:.2f} m"))
        if rms is not None and extent > 0.1:
            sigma = math.degrees(rms / extent)
            if sigma > 45.0:
                report.append(f"nejistota azimutu   : NEURČITELNÝ (> ±45°), "
                              f"trasa {extent:.1f} m")
            else:
                report.append(f"nejistota azimutu   : ±{sigma:.1f}° "
                              f"(trasa {extent:.1f} m)")
        report.append(f"posun výšky         : {alt_offset:+.2f} m")
    report.append(f"snímků v trajektorii: {len(rows)}")
    report.append(f"EXIF bloků           : {stamped}")
    if args.xmp:
        report.append(f"XMP souborů         : {xmp_count}")

    with open(os.path.join(rs_dir, "jak-importovat.txt"), "w") as fh:
        fh.write(instructions_text(local, epsg, zone, report, args))

    print("\nRealityScan:")
    for line in report:
        print("  " + line)
    if rms is not None and extent > 0.1 and math.degrees(rms / extent) > 25.0:
        print("  POZOR: azimut je z téhle trasy neurčitelný (krátká trasa vs. šum GPS).")
        print("         Pozice kamer tím trpí jen málo, ale otočení modelu k severu")
        print("         z těchto dat nevěř: zadej --heading <stupně>, exportuj")
        print("         s --epsg local, nebo při záznamu projdi delší trasu.")
    return rs_dir


def instructions_text(local, epsg, zone, report, args):
    crs_step = (
        "1) Nastav souřadnicový systém projektu (karta WORKFLOW / ALIGNMENT ->\n"
        "   nastavení projektu) na lokální kartézský systém v metrech.\n"
        if local else
        f"1) Nastav souřadnicový systém projektu (output/project CRS) na EPSG:{epsg}\n"
        f"   (UTM zóna {zone}N, WGS84). Tohle musí být PŘED importem trajektorie.\n"
    )
    return f"""ARTrack -> RealityScan
======================

{chr(10).join(report)}

Co je v této složce
-------------------
trajectory.csv        pozice kamer: name,x,y,z  (oddělovač čárka)
trajectory_space.txt  totéž oddělené mezerami (fallback, kdyby import nebral čárky)
exif_gps.args         argfile pro exiftool - zapíše GPS do EXIFu samotných fotek
xmp/                  (jen s --xmp) XMP sidecary s plnou pózou - EXPERIMENTÁLNÍ
jak-importovat.txt    tento soubor

Cesta A - trajektorie jako pozice kamer (doporučeno)
----------------------------------------------------
{crs_step}2) Načti fotky ze složky frames/.
3) WORKFLOW -> Import Metadata -> Trajectory (ve starších verzích Flight log).
4) Vyber trajectory.csv a jako formát zadej vlastní popis sloupců:

       name x y z

   Pozor: když popis sloupců neodpovídá souboru, import spadne potichu -
   sloupce se jen zahodí a priors nikde nejsou. Po importu zkontroluj
   v inspektoru u pár kamer, že mají vyplněnou prior pozici.
5) V nastavení alignmentu zapni použití camera priors (position prior) -
   bez toho se naimportované pozice při alignmentu ignorují.
6) Align Images.

Co z toho vyleze: měřítko a poloha z AR trajektorie (relativně centimetry),
orientace si RealityScan spočítá sám. Rotace se záměrně neimportují - přesnou
konvenci omega/phi/kappa v RealityScanu jsem neověřil, a špatně zadaná rotace
je horší než žádná.

Cesta B - GPS do EXIFu (nejjednodušší, horší měřítko)
-----------------------------------------------------
Ve složce frames/ spusť:

       exiftool -@ ../export/realityscan/exif_gps.args

(cestu uprav podle toho, odkud pouštíš). RealityScan pak GPS z EXIFu použije
jako position prior automaticky. Přesnost je přesnost telefonního GPS,
tj. jednotky metrů - dobré na posazení modelu do mapy, slabé na měřítko.
Obě cesty jdou zkombinovat: EXIF na georeferenci, trajektorie na měřítko.

Cesta C - XMP sidecary s plnou pózou (EXPERIMENTÁLNÍ)
-----------------------------------------------------
Spusť export s --xmp, zkopíruj xmp/*.xmp vedle fotek do frames/ (stejný název,
jiná přípona) a načti fotky znovu. RealityScan XMP načítá automaticky.
xcr:PosePrior="initial" znamená, že pózu může alignment dál upravovat.

NEOVĚŘENO: přesnou konvenci matice xcr:Rotation (world-to-camera vs opačně)
jsem si nemohl potvrdit v nápovědě RealityScanu. Vyzkoušej to na 10 fotkách:
pokud kamery míří opačně nebo je model zrcadlený, exportuj znovu s přepínačem
--xmp-transpose a porovnej. Dokud to nesedí, drž se cesty A.

Jak se počítá transformace AR -> {('lokální systém' if local else f'EPSG:{epsg}')}
-----------------------------------------------------------------------
AR trajektorie je v metrech, ale její azimut je libovolný (ARCore nemá kompas).
Z GPS fixů se proto odhadne jen otočení kolem svislé osy a posun; měřítko se
nefituje (nechává se 1), protože AR měřítko je o dva řády lepší než GPS.
RMS výše říká, jak dobře GPS a AR trajektorie sedí - typicky jednotky metrů.
Krátká trasa (pár metrů) azimut neurčí; pak použij --heading <stupně> nebo
--epsg local.
"""


# ----------------------------------------------------------------------- main

def write_colmap(out_dir, pairs, width, height):
    """pairs: list of (image_name, row dict with tx..qw and fx..cy)."""
    colmap_dir = os.path.join(out_dir, "colmap")
    os.makedirs(colmap_dir, exist_ok=True)

    fx = sum(float(r["fx"]) for _, r in pairs) / len(pairs)
    fy = sum(float(r["fy"]) for _, r in pairs) / len(pairs)
    cx = sum(float(r["cx"]) for _, r in pairs) / len(pairs)
    cy = sum(float(r["cy"]) for _, r in pairs) / len(pairs)
    spread = max(abs(float(r["fx"]) - fx) for _, r in pairs) / fx if fx else 0.0
    if spread > 0.01:
        print(f"WARNING: focal length varies by {spread * 100:.1f} % (autofocus was on) - "
              f"let the SfM software refine the intrinsics")

    with open(os.path.join(colmap_dir, "cameras.txt"), "w") as fh:
        fh.write("# Camera list with one line of data per camera:\n")
        fh.write("#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
        fh.write(f"1 PINHOLE {width} {height} {fx:.4f} {fy:.4f} {cx:.4f} {cy:.4f}\n")

    with open(os.path.join(colmap_dir, "images.txt"), "w") as fh:
        fh.write("# Image list with two lines of data per image:\n")
        fh.write("#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        fh.write("#   POINTS2D[] as (X, Y, POINT3D_ID)\n")
        for image_id, (name, row) in enumerate(pairs, start=1):
            q, t = arcore_to_colmap(
                float(row["tx"]), float(row["ty"]), float(row["tz"]),
                float(row["qx"]), float(row["qy"]), float(row["qz"]), float(row["qw"]),
            )
            fh.write(f"{image_id} {q[0]:.9f} {q[1]:.9f} {q[2]:.9f} {q[3]:.9f} "
                     f"{t[0]:.6f} {t[1]:.6f} {t[2]:.6f} 1 {name}\n\n")

    open(os.path.join(colmap_dir, "points3D.txt"), "w").close()
    return fx, fy, cx, cy


def write_geo(out_dir, pairs, fixes):
    path = os.path.join(out_dir, "frames_geo.csv")
    with open(path, "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["name", "lat", "lon", "alt_m", "gps_acc_m",
                         "x_ar", "y_ar", "z_ar", "t_rel_s"])
        missing = 0
        for name, row in pairs:
            lat, lon, alt, acc = row.get("lat"), row.get("lon"), row.get("alt_m"), row.get("gps_acc_m")
            if not lat:
                fix = interpolate_fix(fixes, float(row["t_rel_s"]))
                if fix:
                    lat, lon, alt, acc = (f"{fix[0]:.8f}", f"{fix[1]:.8f}",
                                          f"{fix[2]:.3f}", f"{fix[3]:.2f}")
                else:
                    missing += 1
                    lat = lon = alt = acc = ""
            writer.writerow([name, lat, lon, alt, acc,
                             row["tx"], row["ty"], row["tz"], row["t_rel_s"]])
    if missing:
        print(f"note: {missing} images have no GPS position")
    return path


def pick_size(rows, meta, key, video=None):
    for row in rows:
        if row.get("width") and row.get("height"):
            return int(row["width"]), int(row["height"])
    info = meta.get(key) or {}
    if info.get("width") and info.get("height"):
        return int(info["width"]), int(info["height"])
    if video:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=width,height", "-of", "csv=p=0:s=,", video],
            check=True, capture_output=True, text=True).stdout.strip()
        return (int(v) for v in probe.split(","))
    raise SystemExit("cannot determine the image size")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", help="session folder (holds frames.csv / pose.csv)")
    ap.add_argument("--step", type=int, default=1,
                    help="use every Nth image (default 1; in video mode 10)")
    ap.add_argument("--out", default=None, help="output folder (default <session>/export)")
    ap.add_argument("--from-video", action="store_true",
                    help="ignore the stills and cut frames out of video.mp4 instead")
    ap.add_argument("--quality", type=int, default=2,
                    help="video mode: ffmpeg JPEG quality, 2 = best (default 2)")
    ap.add_argument("--max-frames", type=int, default=0,
                    help="video mode: stop after N images")
    ap.add_argument("--keep-untracked", action="store_true",
                    help="keep frames whose tracking_state is not TRACKING")
    ap.add_argument("--epsg", default="auto",
                    help="RealityScan trajectory CRS: auto (UTM zone from the GPS), "
                         "local (plain metres, no georeferencing), or an EPSG code "
                         "such as 32633")
    ap.add_argument("--heading", type=float, default=None,
                    help="azimuth of the AR +X axis in degrees from east; overrides "
                         "the fit from the GPS track")
    ap.add_argument("--max-gps-error", type=float, default=20.0,
                    help="ignore GPS fixes with a worse horizontal accuracy [m] "
                         "when fitting (default 20)")
    ap.add_argument("--xmp", action="store_true",
                    help="also write experimental RealityScan XMP sidecars with full poses")
    ap.add_argument("--xmp-transpose", action="store_true",
                    help="transpose the XMP rotation matrix (try this if the cameras "
                         "come out mirrored)")
    args = ap.parse_args()

    session = os.path.abspath(args.session)
    if not os.path.isdir(session):
        sys.exit(f"not a folder: {session}")

    meta = {}
    meta_path = os.path.join(session, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path) as fh:
            meta = json.load(fh)

    frames_csv = os.path.join(session, "frames.csv")
    stills = read_poses(frames_csv) if os.path.exists(frames_csv) else []
    fixes = read_fixes(os.path.join(session, "gps.csv"))
    out = args.out or os.path.join(session, "export")
    os.makedirs(out, exist_ok=True)

    print(f"session        : {os.path.basename(session)}")
    print(f"stills         : {len(stills)}")
    print(f"gps fixes      : {len(fixes)}")

    if stills and not args.from_video:
        # ---------------------------------------------------------- photo mode
        frames_dir = os.path.join(session, "frames")
        pairs = []
        for i, row in enumerate(stills):
            if args.step > 1 and i % args.step:
                continue
            if not args.keep_untracked and row.get("tracking_state") != "TRACKING":
                continue
            if not os.path.exists(os.path.join(frames_dir, row["image_name"])):
                continue
            pairs.append((row["image_name"], row))
        if not pairs:
            sys.exit("no usable photo/pose pairs found")
        width, height = pick_size(stills, meta, "photo_intrinsics")
        print(f"using          : {len(pairs)} photos from frames/ ({width}x{height})")
        write_colmap(out, pairs, width, height)
        geo = write_geo(out, pairs, fixes)
        traj_rows = read_poses(os.path.join(session, "pose.csv")) \
            if os.path.exists(os.path.join(session, "pose.csv")) else stills
        write_realityscan(out, pairs, fixes, traj_rows, args, meta)
        print(f"\nwritten to     : {out}")
        print("  colmap/          cameras.txt, images.txt, points3D.txt")
        print("  realityscan/     trajectory.csv, exif_gps.args, jak-importovat.txt")
        print(f"  {os.path.basename(geo)}   WGS84 + local AR position per image")
        print(f"\nCOLMAP image_path: {frames_dir}")
        print("  colmap feature_extractor --database_path db.db \\")
        print(f"      --image_path {frames_dir} \\")
        print("      --ImageReader.camera_model PINHOLE")
        return

    # ------------------------------------------------------------- video mode
    video = os.path.join(session, "video.mp4")
    pose_csv = os.path.join(session, "pose.csv")
    for required in (video, pose_csv):
        if not os.path.exists(required):
            sys.exit(f"missing {required}")
    poses = read_poses(pose_csv)
    step = args.step if args.step > 1 else 10
    images_dir = os.path.join(out, "images")
    print(f"pose rows      : {len(poses)}")
    print(f"extracting every {step}. video frame ...")
    names = extract_frames(video, images_dir, step, args.quality, args.max_frames)
    print(f"frames written : {len(names)}")

    pairs = []
    for k, name in enumerate(names):
        index = k * step
        if index >= len(poses):
            os.remove(os.path.join(images_dir, name))
            continue
        row = poses[index]
        if not args.keep_untracked and row.get("tracking_state") != "TRACKING":
            os.remove(os.path.join(images_dir, name))
            continue
        pairs.append((name, row))
    if not pairs:
        sys.exit("no usable frame/pose pairs - was the session tracking at all?")
    print(f"usable pairs   : {len(pairs)}")

    width, height = pick_size([], meta, "video_intrinsics", video)
    write_colmap(out, pairs, width, height)
    geo = write_geo(out, pairs, fixes)
    write_realityscan(out, pairs, fixes, poses, args, meta)
    print(f"\nwritten to     : {out}")
    print("  images/          frames cut out of the video")
    print("  colmap/          cameras.txt, images.txt, points3D.txt")
    print("  realityscan/     trajectory.csv, exif_gps.args, jak-importovat.txt")
    print(f"  {os.path.basename(geo)}   WGS84 + local AR position per image")
    print(f"\nCOLMAP image_path: {images_dir}")


if __name__ == "__main__":
    main()

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


def read_fixes(path):
    fixes = []
    if not os.path.exists(path):
        return fixes
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            try:
                fixes.append((
                    float(row["t_rel_s_approx"]),
                    float(row["lat"]),
                    float(row["lon"]),
                    float(row["alt_m"]),
                    float(row["h_acc_m"]),
                ))
            except (KeyError, ValueError):
                continue
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
        print(f"\nwritten to     : {out}")
        print("  colmap/          cameras.txt, images.txt, points3D.txt")
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
    print(f"\nwritten to     : {out}")
    print("  images/          frames cut out of the video")
    print("  colmap/          cameras.txt, images.txt, points3D.txt")
    print(f"  {os.path.basename(geo)}   WGS84 + local AR position per image")
    print(f"\nCOLMAP image_path: {images_dir}")


if __name__ == "__main__":
    main()

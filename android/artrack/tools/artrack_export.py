#!/usr/bin/env python3
"""
Cut frames out of an ARTrack session and export the camera poses.

An ARTrack session folder contains video.mp4 plus pose.csv / gps.csv / meta.json.
Row i of pose.csv belongs to video frame i, so selecting every Nth row and every
Nth video frame keeps images and poses in sync.

Outputs (into <session>/export by default):
    images/frame_000000.jpg ...      extracted frames
    colmap/cameras.txt               one PINHOLE camera
    colmap/images.txt                world-to-camera poses, COLMAP convention
    colmap/points3D.txt              empty, so the folder can be imported as-is
    frames_geo.csv                   per image: WGS84 lat/lon/alt + local AR XYZ

Requires ffmpeg on PATH. Standard library only.

    python3 artrack_export.py /path/to/20261127_143512 --step 10
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import shutil
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

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", help="session folder with video.mp4 and pose.csv")
    ap.add_argument("--step", type=int, default=10,
                    help="use every Nth video frame (default 10)")
    ap.add_argument("--out", default=None, help="output folder (default <session>/export)")
    ap.add_argument("--quality", type=int, default=2,
                    help="ffmpeg JPEG quality, 2 = best, 31 = worst (default 2)")
    ap.add_argument("--max-frames", type=int, default=0, help="stop after N images")
    ap.add_argument("--keep-untracked", action="store_true",
                    help="keep frames whose tracking_state is not TRACKING")
    args = ap.parse_args()

    session = os.path.abspath(args.session)
    video = os.path.join(session, "video.mp4")
    pose_csv = os.path.join(session, "pose.csv")
    for required in (video, pose_csv):
        if not os.path.exists(required):
            sys.exit(f"missing {required}")

    meta = {}
    meta_path = os.path.join(session, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path) as fh:
            meta = json.load(fh)

    poses = read_poses(pose_csv)
    fixes = read_fixes(os.path.join(session, "gps.csv"))
    out = args.out or os.path.join(session, "export")
    images_dir = os.path.join(out, "images")
    colmap_dir = os.path.join(out, "colmap")
    os.makedirs(colmap_dir, exist_ok=True)

    print(f"session        : {os.path.basename(session)}")
    print(f"pose rows      : {len(poses)}")
    print(f"gps fixes      : {len(fixes)}")
    print(f"extracting every {args.step}. frame ...")

    names = extract_frames(video, images_dir, args.step, args.quality, args.max_frames)
    print(f"frames written : {len(names)}")

    pairs = []
    for k, name in enumerate(names):
        index = k * args.step
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

    width = int(meta.get("video_intrinsics", {}).get("width") or 0)
    height = int(meta.get("video_intrinsics", {}).get("height") or 0)
    if not (width and height):
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=width,height", "-of", "csv=p=0:s=,", video],
            check=True, capture_output=True, text=True).stdout.strip()
        width, height = (int(v) for v in probe.split(","))

    fx = sum(float(r["fx"]) for _, r in pairs) / len(pairs)
    fy = sum(float(r["fy"]) for _, r in pairs) / len(pairs)
    cx = sum(float(r["cx"]) for _, r in pairs) / len(pairs)
    cy = sum(float(r["cy"]) for _, r in pairs) / len(pairs)
    spread = max(abs(float(r["fx"]) - fx) for _, r in pairs) / fx if fx else 0.0
    if spread > 0.01:
        print(f"WARNING: focal length varies by {spread * 100:.1f} % "
              f"(autofocus was on) - let the SfM software refine the intrinsics")

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

    with open(os.path.join(out, "frames_geo.csv"), "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["name", "lat", "lon", "alt_m", "gps_acc_m",
                         "x_ar", "y_ar", "z_ar", "t_rel_s"])
        for name, row in pairs:
            t_rel = float(row["t_rel_s"])
            fix = interpolate_fix(fixes, t_rel)
            lat, lon, alt, acc = fix if fix else ("", "", "", "")
            writer.writerow([
                name,
                f"{lat:.8f}" if fix else "",
                f"{lon:.8f}" if fix else "",
                f"{alt:.3f}" if fix else "",
                f"{acc:.2f}" if fix else "",
                row["tx"], row["ty"], row["tz"], row["t_rel_s"],
            ])

    print(f"\nwritten to     : {out}")
    print("  images/          frames for the photogrammetry run")
    print("  colmap/          cameras.txt, images.txt, points3D.txt")
    print("  frames_geo.csv   WGS84 position + local AR position per image")
    if shutil.which("colmap") is None:
        print("\n(colmap is not on PATH here - import the folder on your workstation)")


if __name__ == "__main__":
    main()

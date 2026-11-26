package cz.photoscanar.project

/** The README that ships inside every exported project. */
object ReadmeText {

    fun build(projectName: String, photoCount: Int): String = """
PhotoScan AR - project $projectName
===========================================================

CONTENTS
  images/            $photoCount original JPEG photographs, byte-for-byte as the
                     camera produced them. No AR graphics, no overlays, no
                     re-encoding. EXIF from the camera pipeline is preserved.
  cameras.csv        one row per photograph: timestamps, pixel size, real camera
                     parameters read back from the capture result, and the
                     APPROXIMATE AR pose.
  trajectory.csv     the full pose stream during the session, not only the
                     instants when a photo was taken.
  detections.csv     AprilTag 36h11 detections with pixel coordinates.
  project.json       device, camera, session, coverage and quality summary.
  metadata/          tag distances typed in by the operator, coverage summary,
                     per-photo quality results and the session log.

HOW TO USE THIS IN RealityScan Desktop
  1. Copy the whole project folder to the PC (or unzip the exported archive).
  2. In RealityScan: Workflow -> Add Imagery -> Folder, and point it at images/.
  3. Align images with the normal settings. Do NOT import the AR poses as
     ground-control or as known camera positions - see the warning below.
  4. If you used coded targets, the detections in detections.csv can be used to
     build your own control/scale constraints once you have measured the real
     distances between the targets. metadata/tag_distances.csv holds the
     distances you typed into the app.

HOW TO USE THIS IN COLMAP
  1. colmap feature_extractor --database_path db.db --image_path images
  2. colmap exhaustive_matcher --database_path db.db     (or sequential_matcher,
     which suits a walk-around sequence better and is much faster)
  3. colmap mapper --database_path db.db --image_path images --output_path sparse
  Because every photo in a session comes from the same camera at the same
  resolution, a single shared camera model is appropriate.

IMPORTANT - WHAT THE AR DATA IS AND IS NOT
  The ar_* columns come from ARCore visual-inertial odometry. They are:
    * expressed in a session-local frame whose origin is where the session
      started, Y up, right-handed, metres;
    * approximate - they drift over a walk, especially around featureless
      surfaces, in low light, and after any tracking interruption;
    * NOT geodetic coordinates, NOT surveyed exterior orientation, and NOT
      a substitute for the bundle adjustment your photogrammetry software does.
  Rows with ar_pose_valid = 0 have no usable pose at all.
  Use them to check that you walked all the way around, to visualise where the
  photos came from, or as a rough initialisation. Nothing more.

COVERAGE FIGURES
  project.json reports two different things:
    * coverage.geometric_voxels - distinct viewpoints counted over ARCore
      feature points. Geometry-based, but still only "this voxel was inside the
      field of view of N photos".
    * coverage.direction_shell_estimate - inferred from the phone's path alone.
      This one knows nothing about the object and is purely indicative.
  Neither figure proves that a reconstruction will succeed.

QUALITY FLAGS
  Photos flagged BLURRY / DARK / BRIGHT / DUPLICATE are still here. The app
  never deletes an original. Decide yourself whether to exclude them; for
  photogrammetry it is usually worth removing the clearly blurred ones before
  alignment.

SHOOTING ADVICE THAT ACTUALLY MATTERS
  * Keep 80-85 % overlap between neighbouring photos.
  * Walk a full ring around the object, then a second ring at a different
    height or tilt. Rings alone rarely cover the top.
  * Keep the subject at a roughly constant distance within a ring.
  * Move smoothly; stop rotating while the shutter is firing.
  * Avoid changing exposure mid-ring: lock AE/AF in the app once the light is
    representative.
  * Overcast light is better than hard sun. Avoid moving shadows.
""".trimIndent()
}

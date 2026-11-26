package cz.photoscanar.session

/**
 * Geometry handed to the GL overlay. Plain float arrays so the render thread
 * never has to touch the live model objects or take a lock.
 */
class SceneSnapshot(
    /** xyz triplets forming the trajectory polyline */
    val trajectory: FloatArray,
    /** xyz triplets, one per saved photo */
    val captureMarkers: FloatArray,
    /** rgb triplets matching [captureMarkers]; green = accepted, amber = flagged */
    val captureColors: FloatArray,
    /** pairs of xyz: start and end of each short view-direction ray */
    val viewRays: FloatArray,
    /** xyz triplets of coverage voxel centres */
    val coverageVoxels: FloatArray,
    /** rgb triplets matching [coverageVoxels] */
    val coverageColors: FloatArray,
    /** xyz, the assumed object centre, or null */
    val objectCenter: FloatArray?,
    /** xyz, where the operator should move next, or null */
    val adviceTarget: FloatArray?
) {
    companion object {
        val EMPTY = SceneSnapshot(
            FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
            FloatArray(0), FloatArray(0), null, null
        )
    }
}

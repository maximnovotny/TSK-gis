package cz.photoscanar.project

import java.io.File

/**
 * On-disk layout of one capture project.
 *
 * ```
 * PhotoScanAR_Project_001/
 *   images/                 original JPEGs, exactly as the camera produced them
 *   metadata/               supporting files (tags, coverage, session log, readme)
 *   project.json            session, device and camera description
 *   cameras.csv             one row per photo: time, size, camera params, AR pose
 *   trajectory.csv          the full pose stream, not only the photo instants
 *   detections.csv          AprilTag detections
 * ```
 */
class ProjectLayout(val root: File) {

    val images = File(root, "images")
    val metadata = File(root, "metadata")

    val projectJson = File(root, "project.json")
    val camerasCsv = File(root, "cameras.csv")
    val trajectoryCsv = File(root, "trajectory.csv")
    val detectionsCsv = File(root, "detections.csv")

    val tagDistancesCsv = File(metadata, "tag_distances.csv")
    val coverageJson = File(metadata, "coverage.json")
    val sessionLog = File(metadata, "session_log.txt")
    val readme = File(metadata, "README_RealityScan.txt")

    val name: String get() = root.name

    fun prepare(): ProjectLayout {
        root.mkdirs()
        images.mkdirs()
        metadata.mkdirs()
        return this
    }

    fun imageFile(index: Int, extension: String = "jpg"): File =
        File(images, imageName(index, extension))

    fun photoCount(): Int = images.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.size ?: 0

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val PREFIX = "PhotoScanAR_Project_"

        fun imageName(index: Int, extension: String = "jpg"): String =
            "IMG_%05d.%s".format(index, extension)

        /** Next free project directory, numbered from the highest existing one. */
        fun createNext(projectsDir: File): ProjectLayout {
            projectsDir.mkdirs()
            val used = projectsDir.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) }
                ?.mapNotNull { it.name.removePrefix(PREFIX).toIntOrNull() }
                ?: emptyList()
            var next = (used.maxOrNull() ?: 0) + 1
            var dir = File(projectsDir, PREFIX + "%03d".format(next))
            while (dir.exists()) {
                next++
                dir = File(projectsDir, PREFIX + "%03d".format(next))
            }
            return ProjectLayout(dir).prepare()
        }

        fun list(projectsDir: File): List<ProjectLayout> =
            projectsDir.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) }
                ?.sortedByDescending { it.name }
                ?.map { ProjectLayout(it) }
                ?: emptyList()
    }
}

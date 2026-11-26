package cz.photoscanar

import cz.photoscanar.coverage.CoverageSummary
import cz.photoscanar.geom.Quat
import cz.photoscanar.geom.Vec3
import cz.photoscanar.model.CaptureMode
import cz.photoscanar.model.PhotoRecord
import cz.photoscanar.model.PoseQuality
import cz.photoscanar.model.PoseSample
import cz.photoscanar.model.QualityFlag
import cz.photoscanar.model.QualityMetrics
import cz.photoscanar.model.TagObservation
import cz.photoscanar.project.CsvAppender
import cz.photoscanar.project.Iso8601
import cz.photoscanar.project.JsonWriter
import cz.photoscanar.project.MetadataWriters
import cz.photoscanar.project.ProjectDescriptor
import cz.photoscanar.project.ProjectLayout
import cz.photoscanar.project.ProjectWriter
import cz.photoscanar.project.ReadmeText
import cz.photoscanar.project.ZipExporter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random

class ProjectIoTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------------- layout

    @Test fun projectsAreNumberedFromOne() {
        val dir = temp.newFolder("projects")
        val a = ProjectLayout.createNext(dir)
        val b = ProjectLayout.createNext(dir)
        val c = ProjectLayout.createNext(dir)
        assertEquals("PhotoScanAR_Project_001", a.name)
        assertEquals("PhotoScanAR_Project_002", b.name)
        assertEquals("PhotoScanAR_Project_003", c.name)
        assertTrue(a.images.isDirectory)
        assertTrue(a.metadata.isDirectory)
    }

    @Test fun numberingContinuesAfterAGap() {
        val dir = temp.newFolder("projects")
        File(dir, "PhotoScanAR_Project_007").mkdirs()
        File(dir, "PhotoScanAR_Project_002").mkdirs()
        File(dir, "something_else").mkdirs()
        assertEquals("PhotoScanAR_Project_008", ProjectLayout.createNext(dir).name)
    }

    @Test fun imageNamesAreZeroPaddedAndSortable() {
        assertEquals("IMG_00001.jpg", ProjectLayout.imageName(1))
        assertEquals("IMG_00042.jpg", ProjectLayout.imageName(42))
        assertEquals("IMG_12345.jpg", ProjectLayout.imageName(12345))
        val names = listOf(2, 10, 1, 100).map { ProjectLayout.imageName(it) }
        assertEquals(names.sorted(), names.sortedBy { it.removePrefix("IMG_").removeSuffix(".jpg").toInt() })
    }

    @Test fun listingReturnsNewestFirst() {
        val dir = temp.newFolder("projects")
        ProjectLayout.createNext(dir)
        ProjectLayout.createNext(dir)
        val list = ProjectLayout.list(dir)
        assertEquals(2, list.size)
        assertEquals("PhotoScanAR_Project_002", list[0].name)
    }

    // ---------------------------------------------------------------------- csv

    @Test fun csvWritesItsHeaderOnceAndAppendsAfterReopening() {
        val f = File(temp.newFolder(), "t.csv")
        CsvAppender(f, listOf("a", "b")).use { it.append(listOf(1, "x")) }
        CsvAppender(f, listOf("a", "b")).use { it.append(listOf(2, "y")) }
        val lines = f.readLines()
        assertEquals(listOf("a,b", "1,x", "2,y"), lines)
    }

    @Test fun csvEscapesSeparatorsQuotesAndNewlines() {
        assertEquals("plain", CsvAppender.escape("plain"))
        assertEquals("\"a,b\"", CsvAppender.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvAppender.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvAppender.escape("two\nlines"))
    }

    @Test fun csvFormatsNumbersWithoutLocaleSurprises() {
        assertEquals("1.5", CsvAppender.format(1.5))
        assertEquals("2", CsvAppender.format(2.0))
        assertEquals("0.000123", CsvAppender.format(0.000123))
        assertEquals("", CsvAppender.format(null))
        assertEquals("", CsvAppender.format(Double.NaN))
        assertEquals("1", CsvAppender.format(true))
        assertEquals("0", CsvAppender.format(false))
    }

    @Test fun csvRejectsARowOfTheWrongWidth() {
        val f = File(temp.newFolder(), "t.csv")
        CsvAppender(f, listOf("a", "b")).use { appender ->
            try {
                appender.append(listOf(1))
                throw AssertionError("a short row must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("2 columns"))
            }
        }
    }

    @Test fun everyMetadataRowMatchesItsHeader() {
        assertEquals(MetadataWriters.CAMERAS_HEADER.size, MetadataWriters.cameraRow(photo(1)).size)
        assertEquals(
            MetadataWriters.TRAJECTORY_HEADER.size,
            MetadataWriters.trajectoryRow(pose(1_000), 1L).size
        )
        assertEquals(MetadataWriters.DETECTIONS_HEADER.size, MetadataWriters.detectionRow(tag()).size)
    }

    @Test fun invalidPosesAreMarkedAsSuchInTheExport() {
        val header = MetadataWriters.CAMERAS_HEADER
        val validIdx = header.indexOf("ar_pose_valid")
        val qualityIdx = header.indexOf("ar_pose_quality")
        val txIdx = header.indexOf("ar_tx_approx")

        val tracked = MetadataWriters.cameraRow(photo(1, pose(5_000)))
        assertEquals(true, tracked[validIdx])
        assertEquals("VALID_TRACKING", tracked[qualityIdx])

        val lost = MetadataWriters.cameraRow(
            photo(2, PoseSample(5_000, Vec3.ZERO, Quat.IDENTITY, PoseQuality.INVALID, "STOPPED", "EXCESSIVE_MOTION"))
        )
        assertEquals(false, lost[validIdx])
        assertEquals("INVALID", lost[qualityIdx])

        val noAr = MetadataWriters.cameraRow(photo(3, null))
        assertEquals(false, noAr[validIdx])
        assertEquals("NO_AR", noAr[qualityIdx])
        assertEquals("a missing pose must be an empty cell, never a zero", null, noAr[txIdx])
    }

    @Test fun qualityFlagsAreSerialisedDeterministically() {
        assertEquals("", MetadataWriters.flagsToString(emptySet()))
        assertEquals(
            "BLURRY;DARK",
            MetadataWriters.flagsToString(setOf(QualityFlag.DARK, QualityFlag.BLURRY))
        )
    }

    @Test fun timestampsAreFormattedAsUtc() {
        assertEquals("1970-01-01T00:00:00.000Z", Iso8601.format(0))
        // build the instant in a deliberately non-UTC zone: the export must still
        // render UTC, otherwise metadata from two time zones cannot be compared
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Prague"))
        cal.clear()
        cal.set(2026, java.util.Calendar.NOVEMBER, 25, 13, 34, 56)
        cal.set(java.util.Calendar.MILLISECOND, 789)
        assertEquals("2026-11-25T12:34:56.789Z", Iso8601.format(cal.timeInMillis))
    }

    // --------------------------------------------------------------------- json

    @Test fun jsonWriterProducesBalancedWellFormedOutput() {
        val json = JsonWriter().apply {
            beginObject()
            value("name", "test \"quoted\"")
            value("count", 3)
            value("ratio", 0.5)
            value("flag", true)
            value("missing", null as String?)
            beginObject("nested")
            value("inner", 1L)
            endObject()
            stringArray("list", listOf("a", "b"))
            intArray("ids", listOf(1, 2, 3))
            floatArray("focal", listOf(6.9f))
            endObject()
        }.toString()

        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
        assertTrue(json.contains("\"name\": \"test \\\"quoted\\\"\""))
        assertTrue(json.contains("\"missing\": null"))
        assertTrue(json.contains("\"ratio\": 0.5"))
        assertTrue(json.contains("\"ids\": ["))
        assertTrue(json.contains("6.9"))
        assertFalse("no trailing comma before a closing brace", json.contains(",\n}"))
    }

    @Test fun descriptorRecordsThatArPosesAreApproximate() {
        val json = descriptor().toJson()
        assertTrue(json.contains("\"poses_are_approximate\": true"))
        assertTrue(json.contains("visual-inertial odometry"))
        assertTrue(json.contains("\"originals_deleted\": false"))
        assertTrue(json.contains("\"geometrically_verified\": true"))
        assertTrue(json.contains("\"geometrically_verified\": false"))
        assertTrue(json.contains("\"tag36h11\""))
        assertTrue(json.contains("\"measured_fps_average\""))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test fun readmeTellsTheUserNotToTrustThePoses() {
        val text = ReadmeText.build("PhotoScanAR_Project_001", 120)
        assertTrue(text.contains("PhotoScanAR_Project_001"))
        assertTrue(text.contains("120 original JPEG"))
        assertTrue(text.contains("NOT geodetic coordinates"))
        assertTrue(text.contains("RealityScan"))
        assertTrue(text.contains("COLMAP"))
        assertTrue(text.contains("80-85 %"))
    }

    // ------------------------------------------------------------ project writer

    @Test fun theWriterLeavesValidFilesBehindEvenIfTheSessionIsInterrupted() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        val writer = ProjectWriter(layout)
        writer.appendPhoto(photo(1, pose(1_000)))
        writer.appendPhoto(photo(2, pose(2_000)))
        writer.appendPose(pose(1_000), 1_700_000_000_000L)
        writer.appendDetections(listOf(tag()))
        writer.log("interrupted here")
        writer.flushAll()
        // no close(), no final rewrite: this is the "battery died" case
        val cameras = layout.camerasCsv.readLines()
        assertEquals(MetadataWriters.CAMERAS_HEADER.joinToString(","), cameras[0])
        assertEquals(3, cameras.size)
        assertEquals(2, layout.trajectoryCsv.readLines().size)
        assertEquals(2, layout.detectionsCsv.readLines().size)
        assertTrue(layout.sessionLog.readText().contains("interrupted here"))
        writer.close()
    }

    @Test fun theFinalRewriteMergesTheAsynchronousQualityResults() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        val writer = ProjectWriter(layout)
        val a = photo(1, pose(1_000))
        val b = photo(2, pose(2_000))
        writer.appendPhoto(a)
        writer.appendPhoto(b)
        // quality lands after the rows were already flushed
        b.quality = QualityMetrics(
            analysed = true, laplacianVariance = 123.4, sharpnessScore = 0.5,
            overlapWithPrevious = 0.82, flags = setOf(QualityFlag.BLURRY)
        )
        writer.appendQuality(b)
        writer.rewriteCameras(listOf(a, b))
        writer.close()

        val lines = layout.camerasCsv.readLines()
        assertEquals(3, lines.size)
        val header = lines[0].split(",")
        val row = lines[2].split(",")
        assertEquals("123.4", row[header.indexOf("sharpness_laplacian_var")])
        assertEquals("0.82", row[header.indexOf("overlap_with_previous")])
        assertEquals("BLURRY", row[header.indexOf("quality_flags")])
        assertEquals("0", row[header.indexOf("quality_ok")])
        assertFalse(File(layout.root, "cameras.csv.tmp").exists())
        assertTrue(File(layout.metadata, "quality.csv").readLines().size >= 2)
    }

    @Test fun tagDistancesAreExported() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        ProjectWriter(layout).use { w ->
            w.writeTagDistances(
                listOf(
                    cz.photoscanar.model.TagDistance(3, 7, 1.2345),
                    cz.photoscanar.model.TagDistance(7, 11, 2.0, "wall")
                )
            )
        }
        val lines = layout.tagDistancesCsv.readLines()
        assertEquals("from_tag_id,to_tag_id,distance_m,note", lines[0])
        assertEquals("3,7,1.2345,", lines[1])
        assertEquals("7,11,2,wall", lines[2])
    }

    // ---------------------------------------------------------------------- zip

    @Test fun zipRoundTripsEveryByteAndStoresJpegsUncompressed() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        val rnd = Random(4)
        val photoBytes = ByteArray(40_000) { rnd.nextInt().toByte() }
        layout.imageFile(1).writeBytes(photoBytes)
        layout.imageFile(2).writeBytes(photoBytes)
        layout.camerasCsv.writeText("photo_id,file_name\n1,IMG_00001.jpg\n")
        layout.projectJson.writeText("{\"a\":1}")
        layout.readme.writeText("notes")

        val zip = File(temp.newFolder(), "out.zip")
        var lastDone = 0
        var lastTotal = 0
        val written = ZipExporter.export(layout.root, zip) { done, total, _, _ ->
            lastDone = done; lastTotal = total; true
        }
        assertTrue(written > 0)
        assertEquals(5, lastTotal)
        assertEquals(lastTotal, lastDone)

        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }.sorted()
            assertEquals(
                listOf(
                    "${layout.name}/cameras.csv",
                    "${layout.name}/images/IMG_00001.jpg",
                    "${layout.name}/images/IMG_00002.jpg",
                    "${layout.name}/metadata/README_RealityScan.txt",
                    "${layout.name}/project.json"
                ),
                names
            )
            val jpeg = zf.getEntry("${layout.name}/images/IMG_00001.jpg")
            assertEquals("JPEGs must be stored, not deflated", ZipEntry.STORED.toLong(), jpeg.method.toLong())
            assertArrayEquals(photoBytes, zf.getInputStream(jpeg).readBytes())
            val csv = zf.getEntry("${layout.name}/cameras.csv")
            assertEquals(ZipEntry.DEFLATED.toLong(), csv.method.toLong())
            assertEquals(
                "photo_id,file_name\n1,IMG_00001.jpg\n",
                zf.getInputStream(csv).readBytes().toString(Charsets.UTF_8)
            )
        }
    }

    @Test fun zipExportCanBeAborted() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        repeat(4) { layout.imageFile(it + 1).writeBytes(ByteArray(1000)) }
        val zip = File(temp.newFolder(), "aborted.zip")
        try {
            ZipExporter.export(layout.root, zip) { done, _, _, _ -> done < 2 }
            throw AssertionError("the abort must propagate")
        } catch (expected: ZipExporter.AbortedException) {
            assertTrue(true)
        }
    }

    @Test fun photoCountAndSizeAreReported() {
        val layout = ProjectLayout.createNext(temp.newFolder("projects"))
        layout.imageFile(1).writeBytes(ByteArray(100))
        layout.imageFile(2).writeBytes(ByteArray(200))
        layout.camerasCsv.writeText("x")
        assertEquals(2, layout.photoCount())
        assertEquals(301, layout.totalBytes())
    }

    // ------------------------------------------------------------------ helpers

    private fun pose(tNs: Long) = PoseSample(
        tNs, Vec3(1f, 2f, 3f), Quat(0f, 0f, 0f, 1f),
        PoseQuality.VALID_TRACKING, "TRACKING", "NONE", 0.4f, 0.1f
    )

    private fun photo(id: Int, p: PoseSample? = pose(id * 1000L)) = PhotoRecord(
        id = id,
        fileName = ProjectLayout.imageName(id),
        captureTimestampNs = id * 1000L,
        wallClockEpochMs = 1_700_000_000_000L + id,
        width = 4080, height = 3072, fileSizeBytes = 4_000_000,
        mode = CaptureMode.AUTO_2FPS, cameraId = "0",
        focalLengthMm = 6.9f, fNumber = 1.68f, exposureTimeNs = 4_000_000, iso = 120,
        pose = p
    )

    private fun tag() = TagObservation(
        timestampNs = 1234,
        tagFamily = "tag36h11", tagId = 42,
        cornersX = floatArrayOf(1f, 2f, 3f, 4f), cornersY = floatArrayOf(5f, 6f, 7f, 8f),
        centerX = 2.5f, centerY = 6.5f, sourceWidth = 640, sourceHeight = 480,
        decisionMargin = 0.9f, hammingDistance = 0, source = "preview", photoId = 1
    )

    private fun descriptor() = ProjectDescriptor(
        projectName = "PhotoScanAR_Project_001",
        appVersion = "1.0.0",
        createdEpochMs = 1_700_000_000_000L,
        finishedEpochMs = 1_700_000_060_000L,
        deviceManufacturer = "Google", deviceModel = "Pixel 9",
        androidRelease = "17", androidSdkInt = 37,
        cameraId = "0", cameraFacing = "BACK", sensorOrientationDeg = 90,
        sensorTimestampSource = "REALTIME",
        sensorPhysicalWidthMm = 9.6f, sensorPhysicalHeightMm = 7.2f,
        activeArrayWidth = 4080, activeArrayHeight = 3072,
        availableFocalLengthsMm = listOf(6.9f),
        captureWidth = 4080, captureHeight = 3072, jpegQuality = 97,
        mode = CaptureMode.AUTO_2FPS, targetIntervalMs = 500,
        photosSaved = 120, capturesIssued = 123, capturesDropped = 3,
        measuredFpsAverage = 1.94, measuredFpsPeak = 2.05, sessionDurationMs = 62_000,
        arRequested = true, arUsed = true, arSharedCamera = true, arUnavailableReason = "",
        arPosesValidCount = 1800, arTrackingLostCount = 1,
        tagFamily = "tag36h11", tagDetectionEnabled = true, uniqueTagIds = listOf(3, 7),
        voxelCoverage = CoverageSummary(100, 60, 30, 10, true),
        shellCoverage = CoverageSummary(24, 18, 6, 0, false),
        qualityAnalysisEnabled = true,
        photosFlaggedBlurry = 4, photosFlaggedExposure = 1, photosNotAnalysed = 2,
        exifOrientationPolicy = "from device orientation",
        notes = ProjectDescriptor.DEFAULT_NOTES
    )
}

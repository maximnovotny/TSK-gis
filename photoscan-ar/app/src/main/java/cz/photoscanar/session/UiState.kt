package cz.photoscanar.session

import cz.photoscanar.model.CaptureMode

/** Immutable snapshot of everything the HUD shows. */
data class UiState(
    val recording: Boolean = false,
    val projectName: String = "",
    val mode: CaptureMode = CaptureMode.AUTO_2FPS,
    val photoCount: Int = 0,
    val measuredFps: Double = 0.0,
    val targetFps: Double = 2.0,
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val sharpnessScore: Double = -1.0,
    val lastLaplacianVar: Double = 0.0,
    val overlapPct: Double = -1.0,
    val arEnabled: Boolean = false,
    val arTracking: Boolean = false,
    val arStatusKey: String = "ar_off",
    val arSharedCamera: Boolean = false,
    val voxelGreenPct: Double = 0.0,
    val voxelCells: Int = 0,
    val shellGreenPct: Double = 0.0,
    val adviceKey: String = "",
    val tagCount: Int = 0,
    val tagIdsSeen: Int = 0,
    val storageFreeBytes: Long = 0,
    val estimatedRemainingPhotos: Int = 0,
    val thermalStatus: Int = 0,
    val writeQueueFill: Double = 0.0,
    val writeQueueDepth: Int = 0,
    val pendingAnalysis: Int = 0,
    val movingTooFast: Boolean = false,
    val sessionSeconds: Long = 0,
    val droppedCaptures: Long = 0,
    val notAnalysed: Int = 0,
    val warningKey: String = ""
)

/** What the user is shown when a session ends. */
data class SessionSummary(
    val projectName: String,
    val projectPath: String,
    val photoCount: Int,
    val durationMs: Long,
    val averageFps: Double,
    val blurryCount: Int,
    val exposureFlagged: Int,
    val notAnalysed: Int,
    val tagIds: List<Int>,
    val arUsed: Boolean,
    val bytes: Long
)

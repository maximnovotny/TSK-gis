package cz.photoscanar.model

/**
 * Capture strategies offered on the main screen.
 *
 * [AUTO_2FPS] is the default for ordinary users: a fixed 500 ms cadence.
 * [SMART] shoots based on how far and how much the phone turned, keeping the
 * requested overlap without flooding the project with near-duplicates.
 * [DETAIL] favours the sharpest possible frames (full resolution, never reduced,
 * lower cadence so autofocus and exposure can settle) for rebar, concrete edges
 * and structural detail.
 */
enum class CaptureMode(
    val targetIntervalMs: Long,
    val minIntervalMs: Long,
    val allowResolutionDrop: Boolean,
    val waitForFocusLocked: Boolean
) {
    AUTO_2FPS(targetIntervalMs = 500, minIntervalMs = 450, allowResolutionDrop = true, waitForFocusLocked = false),
    SMART(targetIntervalMs = 500, minIntervalMs = 350, allowResolutionDrop = true, waitForFocusLocked = false),
    DETAIL(targetIntervalMs = 900, minIntervalMs = 700, allowResolutionDrop = false, waitForFocusLocked = true);

    val targetFps: Double get() = 1000.0 / targetIntervalMs
}

package cz.photoscanar.ui

import android.app.Activity
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import cz.photoscanar.databinding.SheetSettingsBinding
import cz.photoscanar.session.CaptureController
import cz.photoscanar.settings.Prefs

/**
 * Capture settings.
 *
 * Toggling ARCore changes which camera configuration is used, so that one asks
 * for a camera restart; everything else takes effect on the next photo.
 */
class SettingsSheet(
    private val activity: Activity,
    private val prefs: Prefs,
    private val controller: CaptureController,
    private val onClosed: (restartNeeded: Boolean) -> Unit
) {
    fun show() {
        val binding = SheetSettingsBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)

        val arBefore = prefs.useAr

        binding.swUseAr.isChecked = prefs.useAr
        binding.swShowAr.isChecked = prefs.showArOverlay
        binding.swTags.isChecked = prefs.detectTags
        binding.swQuality.isChecked = prefs.analyseQuality
        binding.swAntiBlur.isChecked = prefs.antiBlur
        binding.swAdaptRes.isChecked = prefs.adaptResolution
        binding.swExifZero.isChecked = prefs.exifOrientationZero
        binding.swLockAe.isChecked = prefs.lockAe
        binding.swLockAf.isChecked = prefs.lockAf

        binding.seekJpeg.progress = prefs.jpegQuality - 80
        binding.seekDistance.progress = (prefs.objectDistanceM - 1f).toInt().coerceIn(0, 49)
        binding.seekSharp.progress = ((prefs.sharpnessThreshold - 10f) / 5f).toInt().coerceIn(0, 40)

        fun labels() {
            binding.lblJpeg.text = "JPEG ${prefs.jpegQuality} %"
            binding.lblDistance.text = "Vzdálenost objektu %.0f m".format(prefs.objectDistanceM)
            binding.lblSharp.text = "Práh ostrosti %.0f".format(prefs.sharpnessThreshold)
        }
        labels()

        binding.txtDeviceInfo.text = buildString {
            appendLine("ARCore: ${controller.arEngine.availability()}")
            if (prefs.sharedCameraBlacklisted) {
                appendLine("Shared camera: opakovaně selhalo, AR vypnuto")
            }
            appendLine("Projekty: ${controller.projectsDirectory().absolutePath}")
            appendLine("AR pozice jsou přibližné (vizuálně-inerciální odometrie).")
        }

        binding.swUseAr.setOnCheckedChangeListener { _, v -> prefs.useAr = v; if (v) prefs.resetSharedCameraFailures() }
        binding.swShowAr.setOnCheckedChangeListener { _, v -> prefs.showArOverlay = v }
        binding.swTags.setOnCheckedChangeListener { _, v -> prefs.detectTags = v }
        binding.swQuality.setOnCheckedChangeListener { _, v -> prefs.analyseQuality = v }
        binding.swAntiBlur.setOnCheckedChangeListener { _, v -> prefs.antiBlur = v }
        binding.swAdaptRes.setOnCheckedChangeListener { _, v -> prefs.adaptResolution = v }
        binding.swExifZero.setOnCheckedChangeListener { _, v -> prefs.exifOrientationZero = v }
        binding.swLockAe.setOnCheckedChangeListener { _, v -> prefs.lockAe = v }
        binding.swLockAf.setOnCheckedChangeListener { _, v -> prefs.lockAf = v }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                when (sb.id) {
                    binding.seekJpeg.id -> prefs.jpegQuality = 80 + value
                    binding.seekDistance.id -> prefs.objectDistanceM = 1f + value
                    binding.seekSharp.id -> prefs.sharpnessThreshold = 10f + value * 5f
                }
                labels()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        }
        binding.seekJpeg.setOnSeekBarChangeListener(seekListener)
        binding.seekDistance.setOnSeekBarChangeListener(seekListener)
        binding.seekSharp.setOnSeekBarChangeListener(seekListener)

        binding.btnCloseSettings.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClosed(arBefore != prefs.useAr) }
        dialog.show()
    }
}

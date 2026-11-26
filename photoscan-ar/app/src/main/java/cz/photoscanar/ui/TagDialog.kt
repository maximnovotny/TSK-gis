package cz.photoscanar.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import cz.photoscanar.R
import cz.photoscanar.databinding.DialogTagsBinding
import cz.photoscanar.model.TagDistance
import cz.photoscanar.session.CaptureController

/**
 * Coded targets: what has been seen, and the known distances between targets.
 *
 * The distances are the operator's own tape measurements. The app stores and
 * exports them so a scale constraint can be built later in the photogrammetry
 * software - it does not scale the AR trajectory from them and makes no claim
 * about absolute accuracy.
 */
class TagDialog(
    private val activity: Activity,
    private val controller: CaptureController
) {
    fun show() {
        val binding = DialogTagsBinding.inflate(activity.layoutInflater)
        val ids = controller.detectedTagIds()
        binding.txtDetected.text = if (ids.isEmpty()) {
            activity.getString(R.string.tags_none)
        } else {
            "AprilTag 36h11: " + ids.joinToString(", ") { "#$it" }
        }

        fun refreshList() {
            val list = controller.tagDistanceList()
            binding.txtDistances.text = if (list.isEmpty()) "" else
                list.joinToString("\n") { "#${it.fromId} - #${it.toId}  %.3f m".format(it.metres) }
        }
        refreshList()

        binding.btnAddDistance.setOnClickListener {
            val from = binding.editFrom.text.toString().trim().toIntOrNull()
            val to = binding.editTo.text.toString().trim().toIntOrNull()
            val metres = binding.editDistance.text.toString().trim()
                .replace(',', '.').toDoubleOrNull()
            if (from == null || to == null || metres == null || metres <= 0.0) {
                binding.txtDistances.text = "Zadejte dvě ID a vzdálenost v metrech."
                return@setOnClickListener
            }
            controller.addTagDistance(TagDistance(from, to, metres))
            binding.editDistance.text?.clear()
            refreshList()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.tags_title)
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}

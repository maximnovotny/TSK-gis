package cz.tsk.artrack

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * An iOS-style action sheet: a rounded card of choices that slides up from the
 * bottom, with Cancel on its own card below. Replaces the stock AlertDialog,
 * which looked nothing like the rest of the app.
 */
class ActionSheet(context: Context) : Dialog(context, R.style.Theme_ARTrack_ActionSheet) {

    class Choice(val title: String, val destructive: Boolean = false, val action: () -> Unit)

    private var sheetTitle: String? = null
    private val choices = ArrayList<Choice>()

    fun title(text: String) = apply { sheetTitle = text }

    fun choice(title: String, destructive: Boolean = false, action: () -> Unit) =
        apply { choices.add(Choice(title, destructive, action)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = buildContent(context, sheetTitle, choices) { dismiss() }
        setContentView(content)

        window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }


        // Keep the sheet clear of the gesture bar and the rounded screen corners.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                maxOf(safe.bottom, (10 * context.resources.displayMetrics.density).toInt())
            )
            insets
        }
    }

    companion object {
        /**
         * Builds the sheet view. Shared with the screenshot test, so what gets
         * reviewed is the same code that runs on the phone.
         */
        fun buildContent(
            context: Context,
            title: String?,
            choices: List<Choice>,
            onDismiss: () -> Unit
        ): View {
            val inflater = LayoutInflater.from(context)
            val content = inflater.inflate(R.layout.dialog_action_sheet, null)
            val card = content.findViewById<LinearLayout>(R.id.sheetCard)
            // Clip the row highlights to the card's rounded corners.
            card.outlineProvider = ViewOutlineProvider.BACKGROUND
            card.clipToOutline = true

            title?.let { text ->
                val view = inflater.inflate(R.layout.item_action_sheet_title, card, false)
                    as TextView
                view.text = text
                card.addView(view)
                card.addView(
                    inflater.inflate(R.layout.item_action_sheet_separator, card, false)
                )
            }

            for ((index, choice) in choices.withIndex()) {
                if (index > 0) {
                    card.addView(
                        inflater.inflate(R.layout.item_action_sheet_separator, card, false)
                    )
                }
                val view = inflater.inflate(R.layout.item_action_sheet, card, false) as TextView
                view.text = choice.title
                if (choice.destructive) {
                    view.setTextColor(ContextCompat.getColor(context, R.color.system_red))
                }
                view.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDismiss()
                    choice.action()
                }
                card.addView(view)
            }

            content.findViewById<TextView>(R.id.sheetCancel).setOnClickListener { onDismiss() }
            return content
        }
    }
}

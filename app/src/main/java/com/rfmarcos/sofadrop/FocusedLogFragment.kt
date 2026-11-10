package com.rfmarcos.sofadrop

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.DialogFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The last focused cards and what Sofadrop made of them ([FocusedLog]), newest first. */
class FocusedLogFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.focused_log_title),
        getString(R.string.focused_log_desc),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += action(ID_TEXT, R.drawable.ic_act_list, getString(R.string.focused_log_text), getString(R.string.focused_log_text_desc))
            .multilineDescription(true).build()
        actions += action(ID_CLEAR, R.drawable.ic_act_block, getString(R.string.focused_log_clear), null).build()
        // Lines are focusable rows so the remote can scroll through them; they do nothing when chosen.
        // "11-10 10:57  type 1 · "Nocturno" · host → tv/…": the card as the title, what it gave below
        FocusedLog.read(context).asReversed().forEachIndexed { index, line ->
            actions += GuidedAction.Builder(context).id(ID_LINES + index).icon(ColorDrawable(Color.TRANSPARENT))
                .title(line.substringBefore(" → ")).description(line.substringAfter(" → ", "")).multilineDescription(true).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TEXT -> FocusedLogTextDialog().show(parentFragmentManager, "focused_log")
            ID_CLEAR -> {
                FocusedLog.clear(requireContext())
                parentFragmentManager.popBackStack()
            }
        }
    }

    companion object {
        private const val ID_TEXT = 1L
        private const val ID_CLEAR = 2L
        private const val ID_LINES = 100L
    }
}

/**
 * The whole log as plain text, in a dialog like the QR ones, for users to share it (a photo, typing it…):
 * a QR code with the text was hard to scan from the sofa and only held a few cards. Up and down scroll it.
 */
class FocusedLogTextDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = Dialog(requireContext(), R.style.Theme_Sofadrop_QrDialog)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.focused_log_dialog, container, false)
        view.findViewById<TextView>(R.id.log_text).text = FocusedLog.text(requireContext())
        view.findViewById<Button>(R.id.log_close).setOnClickListener { dismissAllowingStateLoss() }
        view.findViewById<View>(R.id.log_scroll).requestFocus()
        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

package com.rfmarcos.sofadrop

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** Which positions a rotating text goes through (see [Settings.POSITION_ROTATE]). */
class RotationFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.position_rotation_title),
        getString(R.string.position_rotation_desc),
        getString(R.string.position_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val chosen = profile.rotation
        Settings.POSITIONS.forEachIndexed { index, position ->
            val covered = position.startsWith("bottom") && !profile.isScreensaver && Projectivy.isInstalled(requireContext())
            actions += checkbox(index.toLong() + 1, DetailsFragment.positionLabel(requireContext(), position),
                getString(R.string.position_rows_note).takeIf { covered }, position in chosen).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val chosen = Settings.POSITIONS.filterIndexed { index, _ -> findActionById(index.toLong() + 1)?.isChecked == true }
        if (chosen.size < 2) {
            action.isChecked = true
            notifyActionChanged(findActionPositionById(action.id))
            Toast.makeText(requireContext(), R.string.position_rotation_min, Toast.LENGTH_SHORT).show()
        } else {
            profile.rotation = chosen
        }
    }
}

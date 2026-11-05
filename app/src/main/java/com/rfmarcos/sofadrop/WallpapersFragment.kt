package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The wallpapers on the home screen, today only in Projectivy Launcher (opened for [Profile.WALLPAPER]).
 * Premium can't be detected, so it's only said; nothing is locked.
 */
class WallpapersFragment : OutputFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.wallpapers_title),
        getString(R.string.projectivy_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    override fun rows(): List<GuidedAction> {
        if (!Projectivy.isInstalled(requireContext())) {
            return listOf(action(ID_GET, R.drawable.ic_act_phone, getString(R.string.projectivy_get), getString(R.string.projectivy_get_desc)).build())
        }
        return listOf(
            action(ID_USE, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title), getString(R.string.use_in_projectivy_desc))
                .multilineDescription(true).build(),
            action(ID_VIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
                .enabled(hasWallpapers()).build(),
        ) + setRows()
    }

    private fun hasWallpapers() = WallpaperStore(requireContext(), profile).current().isNotEmpty()

    // The first set may finish while this screen is open
    override fun refreshStatus() {
        val view = findActionById(ID_VIEW) ?: return
        if (view.isEnabled == hasWallpapers()) return
        view.isEnabled = !view.isEnabled
        notifyActionChanged(findActionPositionById(ID_VIEW))
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_USE -> Projectivy.openWallpaperSettings(requireContext())
            ID_VIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_PROFILE, profile.id))
            ID_GET -> LinkQrDialog.newInstance(Projectivy.URL, R.string.projectivy_get, R.string.projectivy_get_scan)
                .show(parentFragmentManager, "projectivy")
            else -> super.onGuidedActionClicked(action)
        }
    }

    companion object {
        private const val ID_USE = 1L
        private const val ID_GET = 4L
        private const val ID_VIEW = 5L

        /** "In use in Projectivy · Own lists" for the main screen. */
        fun summary(context: Context): String {
            if (!Projectivy.isInstalled(context)) return context.getString(R.string.projectivy_status_optional)
            val status = context.getString(if (Projectivy.inUse(context)) R.string.projectivy_status_in_use else R.string.projectivy_status_pick)
            return listOfNotNull(status, ownSummary(context, Profile.WALLPAPER)).joinToString(" · ")
        }
    }
}

package com.rfmarcos.sofadrop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The wallpapers on the home screen: in Projectivy Launcher, and in other launchers through a folder
 * ([WallpaperFolder]). Opened for [Profile.WALLPAPER]. Projectivy's Premium can't be detected, so it's only said.
 */
class WallpapersFragment : OutputFragment() {

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setFolder(true) else Toast.makeText(requireContext(), R.string.wallpaper_folder_no_permission, Toast.LENGTH_LONG).show()
    }

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
        val context = requireContext()
        val projectivy = Projectivy.isInstalled(context)
        val launcher = if (projectivy) {
            action(ID_USE, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title), getString(R.string.use_in_projectivy_desc))
                .multilineDescription(true).build()
        } else {
            action(ID_GET, R.drawable.ic_act_phone, getString(R.string.projectivy_get), getString(R.string.projectivy_get_desc)).build()
        }
        if (!WallpaperFolder.shown(context)) return listOf(launcher, folderRow())
        return listOf(
            launcher,
            action(ID_VIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
                .enabled(hasWallpapers()).build(),
        ) + folderRow() + setRows()
    }

    /** A copy in Pictures/Sofadrop for launchers that show a folder's images (Monet Launcher...). */
    private fun folderRow(): GuidedAction {
        explanations[ID_FOLDER] = getString(R.string.wallpaper_folder_explain, WallpaperFolder.path)
        return choice(ID_FOLDER, R.drawable.ic_act_download, R.string.wallpaper_folder_title,
            folderLabel(requireContext(), Settings.wallpaperFolder), listOf(false, true).map { folderLabel(requireContext(), it) })
    }

    /** Turn the folder on (the current set goes in, or a first one is made) or off (it's emptied). */
    private fun setFolder(on: Boolean) {
        val context = requireContext().applicationContext
        Settings.wallpaperFolder = on
        when {
            !on -> background({ WallpaperFolder.clear(context) }) {}
            // Without Projectivy there may be no wallpapers' set yet (only the screensaver's)
            WallpaperStore(context, Profile.WALLPAPER).current().isEmpty() -> {
                GenerateWallpapersWorker.schedule(context)
                if (Sources.selected(Profile.WALLPAPER).isNotEmpty()) GenerateWallpapersWorker.runNow(context, Profile.WALLPAPER)
            }
            else -> background({ WallpaperFolder.update(context) }) {}
        }
        refresh()
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_FOLDER) {
            val on = action.id % SUB_ID_BASE == 1L
            if (on == Settings.wallpaperFolder) return true
            if (on && !WallpaperFolder.hasPermission(requireContext())) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else setFolder(on)
            return true
        }
        return super.onSubGuidedActionClicked(action)
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
        private const val ID_FOLDER = 8L

        private fun folderLabel(context: Context, on: Boolean) =
            if (on) context.getString(R.string.wallpaper_folder_on, WallpaperFolder.path) else context.getString(R.string.wallpaper_folder_off)

        /** "In use in Projectivy · Own lists" for the main screen. */
        fun summary(context: Context): String {
            val folder = context.getString(R.string.wallpaper_folder_on, WallpaperFolder.path).takeIf { Settings.wallpaperFolder }
            val status = when {
                Projectivy.isInstalled(context) ->
                    context.getString(if (Projectivy.inUse(context)) R.string.projectivy_status_in_use else R.string.projectivy_status_pick)
                folder == null -> return context.getString(R.string.projectivy_status_optional)
                else -> null
            }
            return listOfNotNull(status, folder, ownSummary(context, Profile.WALLPAPER)).joinToString(" · ")
        }
    }
}

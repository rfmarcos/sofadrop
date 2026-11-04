package com.rfmarcos.sofadrop

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The main screen, the same for everybody: the two places the wallpapers show, each of which can use its own
 * settings, then the general settings they share by default.
 */
class SettingsFragment : PreviewSettingsFragment() {

    private val hasProjectivy by lazy { Projectivy.isInstalled(requireContext()) }

    override val showsProjectivyNotice get() = hasProjectivy && Projectivy.inUse(requireContext())

    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        getString(R.string.plugin_name),
        "",
        getString(R.string.settings),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Delivered on the way back from the notes, before onResume resets the update row: install after it
        parentFragmentManager.setFragmentResultListener(UpdateFragment.RESULT_INSTALL, this) { _, _ -> handler.post { installUpdate() } }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        Settings.init(context)

        if (Updater.available) actions += updateAction()
        actions += action(ID_WALLPAPERS, R.drawable.ic_act_tv, getString(R.string.wallpapers_title), WallpapersFragment.summary(context))
            .multilineDescription(true).hasNext(true).build()
        actions += action(ID_SCREENSAVER, R.drawable.ic_act_screensaver, getString(R.string.screensaver_title), ScreensaverFragment.summary(context))
            .multilineDescription(true).hasNext(true).build()

        actions += header(ID_HEADER_GENERAL, getString(R.string.general_header))
        // "Update now" also carries the status, so there is a single place to look
        actions += action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), generationStatus())
            .multilineDescription(true).build()
        actions += action(ID_PREVIEW, R.drawable.ic_act_preview, getString(R.string.action_preview), getString(R.string.action_preview_desc))
            .enabled(WallpaperStore(context, shownSet).current().isNotEmpty()).build()
        actions += action(ID_LISTS, R.drawable.ic_act_movie, getString(R.string.setting_lists_title), listsText())
            .hasNext(true).build()
        actions += action(ID_DETAILS, R.drawable.ic_act_visibility, getString(R.string.setting_details_title), detailsText())
            .hasNext(true).build()
        actions += action(ID_CLICK, R.drawable.ic_act_play, getString(R.string.click_action_title), clickText())
            .hasNext(true).build()
        actions += choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.screensaver_order_title, orderText(),
            listOf(true, false).map { OutputFragment.mixLabel(context, it) })
        actions += choice(ID_REFRESH, R.drawable.ic_act_schedule, R.string.setting_refresh_title, refreshLabel(Settings.refreshHours),
            Settings.REFRESH_OPTIONS.map(::refreshLabel))

        actions += header(ID_HEADER_MORE, getString(R.string.main_more_header))
        actions += action(ID_ACCOUNTS, R.drawable.ic_act_account, getString(R.string.setting_accounts_title), AccountsFragment.summary(context))
            .hasNext(true).build()
        actions += action(ID_ABOUT, R.drawable.ic_act_info, getString(R.string.setting_about_title),
            getString(R.string.setting_about_desc, BuildConfig.VERSION_NAME)).hasNext(true).build()
    }

    // The left pane's text only shows with the row that explains itself
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = android.view.View.VISIBLE
        // Without Projectivy, Wallpapers only offers to get it: start where there's something to do
        if (savedInstanceState == null && !hasProjectivy) handler.post {
            if (isAdded) findActionPositionById(ID_SCREENSAVER).takeIf { it >= 0 }?.let(::setSelectedActionPosition)
        }
    }

    override fun onResume() {
        super.onResume()
        // Sub-screens may have changed lists, filters or details
        val context = requireContext()
        updateDescription(ID_WALLPAPERS, WallpapersFragment.summary(context))
        updateDescription(ID_SCREENSAVER, ScreensaverFragment.summary(context))
        updateDescription(ID_LISTS, listsText())
        updateDescription(ID_DETAILS, detailsText())
        updateDescription(ID_CLICK, clickText())
        updateDescription(ID_ORDER, orderText())
        updateDescription(ID_ACCOUNTS, AccountsFragment.summary(context))
        handler.post(statusRefresher)
        // Back from the system installer (cancelled, or blocked by Play Protect): offer the update again
        updateDescription(ID_APP_UPDATE, updateText())
        background({ Updater.check() }) { showUpdate() }
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        val context = requireContext()
        GenerateWallpapersWorker.schedule(context, settingsChanged = true)
        // First run: nothing to show in Projectivy yet, so generate right away (the wizard does it with its answers)
        if (shownSet.lastGeneration == 0L && Settings.token.isNotBlank() && !Wizard.open) GenerateWallpapersWorker.runNow(context, shownSet)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_GENERATE -> {
                if ((if (Profile.twoSets) listOf(Profile.WALLPAPER, Profile.SCREENSAVER) else listOf(shownSet)).all { Sources.selected(it).isEmpty() }) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runAll(requireContext())
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
            ID_PREVIEW -> startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_PROFILE, shownSet.id))
            // What's new first, when the release says
            ID_APP_UPDATE -> if (hasNotes()) open(UpdateFragment()) else installUpdate()
            ID_LISTS -> open(ListsFragment())
            ID_DETAILS -> open(DetailsFragment())
            ID_WALLPAPERS -> add(parentFragmentManager, WallpapersFragment().forProfile(Profile.WALLPAPER))
            ID_SCREENSAVER -> add(parentFragmentManager, ScreensaverFragment().forProfile(Profile.SCREENSAVER))
            ID_CLICK -> open(ClickActionFragment())
            ID_ACCOUNTS -> open(AccountsFragment())
            ID_ABOUT -> open(AboutFragment())
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_ORDER -> {
                Settings.mixLists = index == 0
                updateDescription(ID_ORDER, orderText())
            }
            ID_REFRESH -> {
                Settings.refreshHours = Settings.REFRESH_OPTIONS[index]
                updateDescription(ID_REFRESH, refreshLabel(Settings.refreshHours))
                refreshStatus()
            }
        }
        return true
    }

    private fun refreshLabel(hours: Int) =
        if (hours < 48) getString(R.string.refresh_hours, hours) else getString(R.string.refresh_days, hours / 24)

    /** [text], saying so when every place that shows wallpapers has its [own] instead of this general setting. */
    private fun general(id: Long, text: String, own: (Profile) -> Boolean): String {
        val unused = own(Profile.SCREENSAVER) && (!hasProjectivy || own(Profile.WALLPAPER))
        if (unused) explanations[id] = getString(if (hasProjectivy) R.string.general_unused_explain else R.string.general_unused_explain_alone)
        else explanations.remove(id)
        return if (unused) getString(R.string.general_unused, text) else text
    }

    private fun listsText() = general(ID_LISTS, ListsFragment.summary(requireContext(), profile)) { it.ownLists }

    private fun detailsText() = general(ID_DETAILS, DetailsFragment.summary(requireContext(), profile)) { it.ownLook }

    private fun clickText() = general(ID_CLICK, ClickActionFragment.summary(requireContext(), profile)) { it.ownClick }

    private fun orderText() = general(ID_ORDER, OutputFragment.mixLabel(requireContext(), Settings.mixLists)) { it.ownMix != null }

    private fun hasNotes() = Updater.releaseNotes(Settings.updateNotes).isNotEmpty()

    private fun updateText() = getString(if (hasNotes()) R.string.update_desc_notes else R.string.update_desc,
        Settings.updateVersion, BuildConfig.VERSION_NAME)

    // Opens the notes first when the release has them (onGuidedActionClicked)
    private fun updateAction(): GuidedAction =
        action(ID_APP_UPDATE, R.drawable.ic_act_refresh, getString(R.string.update_title), updateText()).multilineDescription(true).build()

    /** Offer an update found after the screen opened, on top, without reopening the screen. */
    private fun showUpdate() {
        if (!isAdded || !Updater.available || findActionById(ID_APP_UPDATE) != null) return
        actions = actions.toMutableList().apply { add(0, updateAction()) }
    }

    /** Download and install the new version; Android first asks to allow Sofadrop to install apps. */
    private fun installUpdate() {
        val context = requireContext().applicationContext
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, R.string.update_allow, Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")))
            return
        }
        updateDescription(ID_APP_UPDATE, getString(R.string.update_downloading, Settings.updateVersion))
        background({ Updater.install(context) }) { result ->
            result.onFailure {
                updateDescription(ID_APP_UPDATE, getString(R.string.update_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    // --- Status and pending changes ---

    private fun refreshStatus() {
        if (!isAdded) return
        updateDescription(ID_GENERATE, generationStatus())
        findActionById(ID_PREVIEW)?.let {
            val enabled = WallpaperStore(requireContext(), shownSet).current().isNotEmpty()
            if (it.isEnabled != enabled) {
                it.isEnabled = enabled
                notifyActionChanged(findActionPositionById(ID_PREVIEW))
            }
        }
        refreshPending()
    }

    companion object {
        private const val ID_GENERATE = 2L
        private const val ID_PREVIEW = 3L
        private const val ID_LISTS = 4L
        private const val ID_REFRESH = 8L
        private const val ID_ABOUT = 18L
        private const val ID_DETAILS = 20L
        private const val ID_APP_UPDATE = 21L
        private const val ID_ACCOUNTS = 23L
        private const val ID_CLICK = 24L
        private const val ID_SCREENSAVER = 25L
        private const val ID_HEADER_GENERAL = 26L
        private const val ID_HEADER_MORE = 27L
        private const val ID_WALLPAPERS = 28L
        private const val ID_ORDER = 29L
    }
}

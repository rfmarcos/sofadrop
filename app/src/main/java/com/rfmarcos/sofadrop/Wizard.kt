package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * The setup wizard: a few questions with the preview in view, opened by itself on the first run and from
 * Settings. Each answer is saved as it is given, in the wallpapers' settings; the finer ones stay in Settings.
 */
object Wizard {

    enum class Step { WELCOME, LISTS, STYLE, DONE }

    private fun fragment(step: Step): WizardStepFragment = when (step) {
        Step.WELCOME -> WizardWelcomeFragment()
        Step.STYLE -> WizardStyleFragment()
        Step.LISTS -> WizardListsFragment()
        Step.DONE -> WizardDoneFragment()
    }

    /** From the welcome screen until it closes: Settings doesn't make a set with the defaults meanwhile. */
    var open = false
        private set

    /** A fresh install, the first time (even when Projectivy already made a set with the defaults). */
    fun shouldOffer() = !Settings.wizardShown && Settings.freshInstall

    /** Opened again from About: skipping it leaves everything as it is. */
    var reopened = false
        private set

    fun start(manager: FragmentManager) {
        open = true
        reopened = Settings.wizardShown
        Settings.wizardShown = true
        GuidedStepSupportFragment.add(manager, fragment(Step.WELCOME))
    }

    fun next(from: WizardStepFragment) {
        Step.entries.getOrNull(from.step.ordinal + 1)?.let { GuidedStepSupportFragment.add(from.parentFragmentManager, fragment(it)) }
    }

    /** Back to the screen the wizard was opened from, and make the set with the answers. */
    fun finish(from: WizardStepFragment) {
        generate(from.requireContext())
        from.popBackStackToGuidedStepSupportFragment(WizardWelcomeFragment::class.java, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    fun closed() {
        open = false
    }

    /** Make the wallpapers' set now, unless it is being made already. */
    fun generate(context: Context) {
        val profile = Profile.GENERAL
        if (profile.generating || Settings.token.isBlank() || Sources.selected(profile).isEmpty()) return
        if (profile.lastGeneration == 0L || profile.hasPendingChanges) GenerateWallpapersWorker.runNow(context, profile, replace = true)
    }

    /** Lists picked together in the lists step; the platforms' ones are MDBList's public lists (no account). */
    class ListPreset(val id: Long, val title: String, val description: String, val keys: List<String>, val mdbLists: List<MdbListRef> = emptyList())

    fun listPresets(context: Context): List<ListPreset> {
        fun mdbList(id: Long, platform: String, vararg paths: String): ListPreset {
            val refs = MdbListClient.SUGGESTED.filter { it.path in paths }
            return ListPreset(id, platform, context.getString(R.string.wizard_lists_platform_desc, platform), refs.map { it.key }, refs)
        }
        return listOf(
            ListPreset(10, context.getString(R.string.wizard_lists_trending), context.getString(R.string.wizard_lists_trending_desc),
                listOf("tmdb:trending_movies", "tmdb:trending_tv")),
            mdbList(11, "Netflix", "garycrawfordgc/netflix-movies", "garycrawfordgc/netflix-shows"),
            mdbList(12, "HBO Max", "garycrawfordgc/hbo-shows"),
            mdbList(13, "Disney+", "garycrawfordgc/disney-shows"),
            ListPreset(14, context.getString(R.string.wizard_lists_top_rated), context.getString(R.string.wizard_lists_top_rated_desc),
                listOf("tmdb:top_rated_movies", "tmdb:top_rated_tv")),
            ListPreset(15, context.getString(R.string.wizard_lists_upcoming), context.getString(R.string.wizard_lists_upcoming_desc),
                listOf("tmdb:upcoming")),
            ListPreset(16, context.getString(R.string.wizard_lists_anime), context.getString(R.string.wizard_lists_anime_desc),
                listOf("simkl:trending_anime")),
        )
    }
}

/** One step: "Step 2 of 4" over its question, its text always in view under the preview, and Next at the end. */
abstract class WizardStepFragment : PreviewSettingsFragment() {

    abstract val step: Wizard.Step
    protected abstract val titleRes: Int
    protected abstract fun text(): String

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(getString(titleRes), text(),
        getString(R.string.wizard_step, step.ordinal + 1, Wizard.Step.entries.size),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = View.VISIBLE
    }

    protected fun nextRow(): GuidedAction = action(ID_NEXT, R.drawable.ic_act_check, getString(R.string.wizard_next), null).build()

    /** One of the step's options, picked like a radio button. */
    protected fun radio(id: Long, title: CharSequence, description: CharSequence?, checked: Boolean) =
        GuidedAction.Builder(requireContext()).id(id).icon(ColorDrawable(Color.TRANSPARENT)).title(title).description(description)
            .checkSetId(RADIO_SET).checked(checked).multilineDescription(true)

    /** Whether the step can be left with Next; says why not. */
    protected open fun ready() = true

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_NEXT && ready()) Wizard.next(this)
    }

    companion object {
        const val ID_NEXT = 99L
        private const val RADIO_SET = 50
    }
}

private const val ID_NEEDS = 90L

class WizardWelcomeFragment : WizardStepFragment() {
    override val step = Wizard.Step.WELCOME
    override val titleRes = R.string.wizard_welcome_title

    override fun text(): String =
        getString(if (Projectivy.isInstalled(requireContext())) R.string.wizard_welcome_projectivy else R.string.wizard_welcome_lead)

    /** What it needs, before any choice and where it can't be missed: a row that takes no focus. */
    private fun needs(): GuidedAction {
        val context = requireContext()
        val screensaver = when (ScreensaverSetupFragment.route(context)) {
            ScreensaverSetupFragment.Route.COMPUTER -> R.string.wizard_welcome_google_tv
            ScreensaverSetupFragment.Route.BY_HAND ->
                if (ScreensaverSetupFragment.oldFireOs(context)) R.string.wizard_welcome_fire_tv_old else R.string.wizard_welcome_fire_tv
            else -> R.string.wizard_welcome_android_tv
        }
        val notice = ContextCompat.getColor(context, R.color.notice)
        fun coloured(text: String) = SpannableString(text).apply { setSpan(ForegroundColorSpan(notice), 0, length, 0) }
        return action(ID_NEEDS, R.drawable.ic_act_info, coloured(getString(R.string.wizard_needs_title)),
            coloured(getString(R.string.wizard_welcome_premium) + "\n" + getString(screensaver)))
            .multilineDescription(true).infoOnly(true).focusable(false).build()
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += needs()
        actions += action(ID_START, R.drawable.ic_act_play, getString(R.string.wizard_start), getString(R.string.wizard_start_desc)).build()
        actions += choice(ID_LANGUAGE, R.drawable.ic_act_language, R.string.general_language_title, Languages.displayName(Settings.language),
            Languages.OPTIONS.values.toList())
        // Another TV already set up: its settings file instead of the questions (Google TV has no picker)
        val canPick = SettingsFile.canPick(requireContext())
        actions += action(ID_IMPORT, R.drawable.ic_act_add, getString(R.string.wizard_import),
            getString(if (canPick) R.string.settings_file_import_desc else R.string.settings_file_import_no_picker, SettingsFile.NAME))
            .multilineDescription(true).focusable(canPick).build()
        actions += if (Wizard.reopened) action(ID_SKIP, R.drawable.ic_act_refresh, getString(R.string.wizard_leave), null).build()
            else action(ID_SKIP, R.drawable.ic_act_refresh, getString(R.string.wizard_skip), getString(R.string.wizard_skip_desc))
                .multilineDescription(true).build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_START -> Wizard.next(this)
            ID_SKIP -> Wizard.finish(this)
            ID_IMPORT -> runCatching { pick.launch(arrayOf("*/*")) }
        }
    }

    private val pick = registerForActivityResult(SettingsFile.Pick()) { uri ->
        if (uri != null) open(SettingsImportFragment.newInstance(uri))
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_LANGUAGE) {
            Settings.language = Languages.OPTIONS.keys.elementAt((action.id % SUB_ID_BASE).toInt())
            updateDescription(ID_LANGUAGE, Languages.displayName(Settings.language))
            schedulePreview(300)
        }
        return true
    }

    override fun onDestroy() {
        Wizard.closed()
        super.onDestroy()
    }

    private companion object {
        const val ID_START = 1L
        const val ID_LANGUAGE = 2L
        const val ID_SKIP = 3L
        const val ID_IMPORT = 4L
    }
}

class WizardStyleFragment : WizardStepFragment() {
    override val step = Wizard.Step.STYLE
    override val titleRes = R.string.wizard_style_title
    override fun text() = getString(R.string.wizard_style_text)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val position = DetailsFragment.positionLabel(context, profile.position)
        return Settings.STYLES.mapIndexed { index, style ->
            // "As set below" means the Details screen: here, that it can be set later
            val description = if (style == Settings.STYLE_FULL) getString(R.string.wizard_style_full_desc) else DetailsFragment.styleDescription(context, style)
            radio(ID_STYLE_BASE + index, DetailsFragment.styleLabel(context, style), description, profile.style == style).build()
        } + action(ID_POSITION, R.drawable.ic_act_place, getString(R.string.position_title), position)
            .subActions(options(ID_POSITION, Settings.POSITIONS.map { DetailsFragment.positionLabel(context, it) }, position,
                Settings.POSITIONS.map { if (it.startsWith("top") || !Projectivy.isInstalled(context)) null else getString(R.string.position_rows_note) }))
            .build().hiddenBy(profile.drawsLogo) + nextRow()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val style = Settings.STYLES.getOrNull((action.id - ID_STYLE_BASE).toInt())
        if (style == null) return super.onGuidedActionClicked(action)
        profile.style = style
        refreshActions(rows())
        schedulePreview(300)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        if (action.id / SUB_ID_BASE == ID_POSITION) {
            profile.position = Settings.POSITIONS[(action.id % SUB_ID_BASE).toInt()]
            refreshActions(rows())
            schedulePreview(300)
        }
        return true
    }

    private companion object {
        const val ID_STYLE_BASE = 10L
        const val ID_POSITION = 20L
    }
}

class WizardListsFragment : WizardStepFragment() {
    override val step = Wizard.Step.LISTS
    override val titleRes = R.string.wizard_lists_title
    // The lists by name: "2 lists" after ticking one option reads like a mistake
    override fun text(): String {
        val context = requireContext()
        val selected = Sources.selected(profile)
        val now = if (selected.isEmpty() || selected.size > 4) ListsFragment.listsCount(context, selected.size)
            else selected.joinToString(", ") { Sources.label(context, it) }
        return getString(R.string.wizard_lists_text) + "\n\n" + getString(R.string.wizard_lists_now, now)
    }

    private val presets by lazy { Wizard.listPresets(requireContext()) }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Back from the accounts or the lists: a list may have been connected or picked
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
        setGuidanceText(text())
    }

    /** The watchlist of the connected Trakt or Simkl account, if any. */
    private fun ownKeys() = when {
        Settings.traktConnected -> listOf("trakt:watchlist_movies", "trakt:watchlist_shows")
        Settings.simklConnected -> listOf("simkl:plantowatch")
        else -> emptyList()
    }

    private fun rows(): List<GuidedAction> {
        val context = requireContext()
        val chosen = profile.lists
        val rows = presets.map { checkbox(it.id, it.title, it.description, it.keys.all(chosen::contains)).build() }.toMutableList()
        val own = ownKeys()
        rows += if (own.isEmpty()) {
            action(ID_CONNECT, R.drawable.ic_act_account, getString(R.string.wizard_lists_own), getString(R.string.wizard_lists_connect))
                .hasNext(true).build()
        } else {
            val labels = Sources.available().filter { it.key in own }.joinToString(" · ") { Sources.label(context, it) }
            checkbox(ID_OWN, getString(R.string.wizard_lists_own), labels, own.all(chosen::contains)).build()
        }
        rows += action(ID_BY_HAND, R.drawable.ic_act_list, getString(R.string.wizard_lists_by_hand), ListsFragment.summary(context, profile))
            .hasNext(true).build()
        rows += nextRow()
        return rows
    }

    override fun ready(): Boolean {
        if (Sources.selected(profile).isNotEmpty()) return true
        Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val preset = presets.firstOrNull { it.id == action.id }
        when {
            preset != null -> {
                if (action.isChecked) Settings.mdbLists += preset.mdbLists.filter { ref -> Settings.mdbLists.none { it.key == ref.key } }
                pick(preset.keys, action.isChecked)
            }
            action.id == ID_OWN -> pick(ownKeys(), action.isChecked)
            action.id == ID_CONNECT -> open(AccountsFragment())
            action.id == ID_BY_HAND -> open(ListsFragment())
            else -> super.onGuidedActionClicked(action)
        }
    }

    private fun pick(keys: List<String>, picked: Boolean) {
        profile.lists = if (picked) profile.lists + keys else profile.lists - keys.toSet()
        refreshActions(rows())
        setGuidanceText(text())
        schedulePreview()
    }

    private companion object {
        const val ID_OWN = 30L
        const val ID_CONNECT = 31L
        const val ID_BY_HAND = 32L
    }
}

class WizardDoneFragment : WizardStepFragment() {
    override val step = Wizard.Step.DONE
    override val titleRes = R.string.wizard_done_title

    override fun text(): String {
        val context = requireContext()
        return listOf(
            getString(R.string.wizard_done_lists, ListsFragment.summary(context, profile)),
            // The summary leaves out the usual style; here it's one of the answers
            getString(R.string.wizard_done_look, listOfNotNull(DetailsFragment.styleLabel(context, profile.style).takeIf { profile.drawsDetails },
                DetailsFragment.summary(context, profile)).joinToString(" · ")),
        ).joinToString("\n")
    }

    private val statusRefresher = object : Runnable {
        override fun run() {
            updateDescription(ID_SETTINGS, sofascreenStatus())
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wizard.generate(requireContext())
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        actions += action(ID_SCREENSAVER, R.drawable.ic_act_screensaver, getString(R.string.sofascreen_switch_on), screensaverStatus())
            .multilineDescription(true).enabled(ScreensaverSetupFragment.canSwitchOn(context)).build()
        if (Projectivy.isInstalled(context)) {
            actions += action(ID_PROJECTIVY, R.drawable.ic_act_tv, getString(R.string.use_in_projectivy_title), getString(R.string.use_in_projectivy_desc))
                .multilineDescription(true).build()
        }
        // With the set's progress
        actions += action(ID_SETTINGS, R.drawable.ic_act_check, getString(R.string.wizard_finish), sofascreenStatus())
            .multilineDescription(true).build()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handler.post { if (isAdded) setSelectedActionPosition(actions.lastIndex) }
    }

    private fun screensaverStatus() = if (ScreensaverSetupFragment.canSwitchOn(requireContext())) ScreensaverSetupFragment.status(requireContext())
    else getString(if (ScreensaverSetupFragment.oldFireOs(requireContext())) R.string.wizard_screensaver_by_hand_old else R.string.wizard_screensaver_by_hand)

    override fun onResume() {
        super.onResume()
        // Back from Android's screensaver settings
        updateDescription(ID_SCREENSAVER, screensaverStatus())
        handler.post(statusRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_PROJECTIVY -> Projectivy.openWallpaperSettings(requireContext())
            ID_SCREENSAVER -> ScreensaverSetupFragment.open(this)
            ID_SETTINGS -> Wizard.finish(this)
        }
    }

    private companion object {
        const val ID_PROJECTIVY = 1L
        const val ID_SCREENSAVER = 2L
        const val ID_SETTINGS = 3L
    }
}

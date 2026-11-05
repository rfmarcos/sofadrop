package com.rfmarcos.sofadrop

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.widget.GuidedAction

/**
 * A place the wallpapers show ([WallpapersFragment], [ScreensaverFragment]): for each thing it shares with the
 * general settings, whether it uses them or its own. Own lists or a look of its own mean a second set on disk.
 */
abstract class OutputFragment : PreviewSettingsFragment() {

    private val statusRefresher = object : Runnable {
        override fun run() {
            if (showsOwnSet) updateDescription(ID_GENERATE, generationStatus())
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private val showsOwnSet get() = Profile.twoSets && profile.source == profile

    protected abstract fun rows(): List<GuidedAction>

    /** Its own status rows, every couple of seconds, row by row so an open list of choices stays open. */
    protected open fun refreshStatus() = Unit

    protected open fun refresh() {
        refreshActions(rows())
        schedulePreview()
    }

    // What the place is for, under the preview
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        guidanceStylist.descriptionView?.visibility = android.view.View.VISIBLE
    }

    /** The rows that say, one by one, what comes from the general settings. */
    protected fun setRows(): List<GuidedAction> {
        val context = requireContext()
        for (id in listOf(ID_OWN_LISTS, ID_OWN_LOOK, ID_GENERATE)) explanations[id] = getString(R.string.output_own_explain)
        val orders = listOf(null, true, false).map { orderLabel(context, it) }
        return listOfNotNull(
            header(ID_HEADER_SET, getString(if (profile.isScreensaver) R.string.screensaver_set_header else R.string.wallpapers_set_header)),
            choice(ID_OWN_LISTS, R.drawable.ic_act_movie, R.string.setting_lists_title, ownLabel(context, profile.ownLists, LISTS), OWNS.map { ownLabel(context, it, LISTS) }),
            action(ID_LISTS, R.drawable.ic_act_list, getString(R.string.output_edit_lists), ListsFragment.summary(context, profile))
                .hasNext(true).build().takeIf { profile.ownLists },
            choice(ID_OWN_LOOK, R.drawable.ic_act_visibility, R.string.setting_details_title, ownLabel(context, profile.ownLook, DETAILS), OWNS.map { ownLabel(context, it, DETAILS) }),
            action(ID_DETAILS, R.drawable.ic_act_tv, getString(R.string.output_edit_look), DetailsFragment.summary(context, profile))
                .hasNext(true).build().takeIf { profile.ownLook },
            choice(ID_OWN_CLICK, R.drawable.ic_act_play, R.string.click_action_title, ownLabel(context, profile.ownClick), OWNS.map { ownLabel(context, it) }),
            action(ID_CLICK, R.drawable.ic_act_play, getString(R.string.output_edit_click), ClickActionFragment.summary(context, profile))
                .hasNext(true).build().takeIf { profile.ownClick },
            choice(ID_ORDER, R.drawable.ic_act_shuffle, R.string.screensaver_order_title, orderLabel(context, profile.ownMix), orders),
            // The general set's state is on the main screen
            action(ID_GENERATE, R.drawable.ic_act_refresh, getString(R.string.action_generate_now), generationStatus())
                .multilineDescription(true).build().takeIf { showsOwnSet },
        )
    }

    override fun onResume() {
        super.onResume()
        // Back from its lists or details, or from Android's screensaver settings
        refresh()
        handler.post(statusRefresher)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRefresher)
        super.onPause()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_LISTS -> open(ListsFragment())
            ID_DETAILS -> open(DetailsFragment())
            ID_CLICK -> open(ClickActionFragment())
            ID_GENERATE -> {
                if (Sources.selected(profile).isEmpty()) {
                    Toast.makeText(requireContext(), R.string.select_list_first, Toast.LENGTH_SHORT).show()
                    return
                }
                GenerateWallpapersWorker.runNow(requireContext(), profile, replace = true)
                Toast.makeText(requireContext(), R.string.generation_started, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        val warning = getString(R.string.output_own_off_confirm)
        when (action.id / SUB_ID_BASE) {
            // Back to the general ones, its own are lost (they start as the general ones when turned on again)
            ID_OWN_LISTS -> {
                if (index == 0 && profile.ownLists && !confirmed(action, warning, inUse = 1)) return false
                setOwn { profile.ownLists = index == 1 }
            }
            ID_OWN_LOOK -> {
                if (index == 0 && profile.ownLook && !confirmed(action, warning, inUse = 1)) return false
                setOwn { profile.ownLook = index == 1 }
            }
            ID_OWN_CLICK -> {
                if (index == 0 && profile.ownClick && !confirmed(action, warning, inUse = 1)) return false
                profile.ownClick = index == 1
            }
            ID_ORDER -> profile.ownMix = listOf(null, true, false)[index]
        }
        refresh() // the rows to edit them follow the choice
        return true
    }

    /**
     * Turn its own lists or look on or off. Once both places use one set again the screensaver's goes (disk),
     * and it is made again from scratch if it comes back; a new one is made when the settings close.
     */
    private fun setOwn(change: () -> Unit) {
        change()
        GenerateWallpapersWorker.dropScreensaverSet(requireContext())
    }

    companion object {
        private const val ID_HEADER_SET = 40L
        private const val ID_OWN_LISTS = 41L
        private const val ID_LISTS = 42L
        private const val ID_OWN_LOOK = 43L
        private const val ID_DETAILS = 44L
        private const val ID_OWN_CLICK = 45L
        private const val ID_CLICK = 46L
        private const val ID_ORDER = 47L
        private const val ID_GENERATE = 48L

        private const val LISTS = 1
        private const val DETAILS = 2
        private val OWNS = listOf(false, true)

        /** The lists and details say so; what it opens and the order, "the general settings". */
        private fun ownLabel(context: Context, own: Boolean, what: Int = 0) = context.getString(
            when (what) {
                LISTS -> if (own) R.string.output_use_own_lists else R.string.output_use_general_lists
                DETAILS -> if (own) R.string.output_use_own_details else R.string.output_use_general_details
                else -> if (own) R.string.output_use_own else R.string.output_use_general
            }
        )

        fun mixLabel(context: Context, mixed: Boolean) = context.getString(if (mixed) R.string.mix_on else R.string.mix_off)

        private fun orderLabel(context: Context, own: Boolean?) = own?.let { mixLabel(context, it) } ?: ownLabel(context, false)

        /** What it has of its own, for its row on the main screen; nothing when it's all general. */
        fun ownSummary(context: Context, profile: Profile): String? = when {
            profile.ownLists && profile.ownLook -> R.string.screensaver_summary_own_both
            profile.ownLists -> R.string.screensaver_summary_own_lists
            profile.ownLook -> R.string.screensaver_summary_own_look
            profile.ownClick || profile.ownMix != null -> R.string.output_summary_own
            else -> null
        }?.let(context::getString)
    }
}

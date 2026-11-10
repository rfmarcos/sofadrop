package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnLayout
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Sofascreen, the screensaver (opened for [Profile.SCREENSAVER]): starting it, making it the TV's, what it takes
 * from the general settings (see [OutputFragment]), the slideshow and the clock. After PR #13 by schumi2004.
 */
class ScreensaverFragment : OutputFragment() {

    /** On the preview: the clock and the brightness, which aren't part of the wallpapers. */
    private var clock: SofascreenClock? = null

    /** The images over the preview while it shows the transition (see [showTransition]). */
    private val layers = mutableListOf<ImageView>()
    private var backdrop: View? = null
    private var hold: Runnable? = null
    private var demo = 0

    /** Null in GitHub builds, where the clock's colours are free. Lazy because the rows are built inside super.onCreate. */
    private val supporter by lazy { createSupporter(requireContext(), ::showSupporter) }
    private var supporterState: Supporter.State? = null
    private val colorsLocked get() = supporter != null && supporterState != Supporter.State.BOUGHT

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val frame = view.findViewById<FrameLayout>(R.id.preview_frame)
        clock = SofascreenClock(context).also {
            frame.addView(it.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        frame.doOnLayout { showClock() }
    }

    override fun onDestroyView() {
        stopTransition()
        clock?.close()
        clock = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        supporter?.refresh()
    }

    override fun onDestroy() {
        supporter?.close()
        super.onDestroy()
    }

    private fun showSupporter(state: Supporter.State) {
        if (state == Supporter.State.UNAVAILABLE) {
            Toast.makeText(requireContext(), R.string.supporter_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        if (state == supporterState) return
        supporterState = state
        // A refund takes the colour away on screen, and a restored backup doesn't bring one; the choice itself stays
        Supporter.remember(state)
        if (view != null) refresh()
    }

    /** The clock on the 16:9 preview, sized as on the screen. */
    private fun showClock() {
        val clock = clock ?: return
        val width = (clock.view.parent as View).width.toFloat()
        if (width == 0f) return
        previewImage?.minimumHeight = (width * 9f / 16f).toInt() // the frame's size, also until the image arrives
        previewImage?.foreground = ColorDrawable(Color.argb((255 * (1f - Settings.screensaverBrightness / 100f)).toInt(), 0, 0, 0))
        clock.build(width, width * 9f / 16f, Profile.SCREENSAVER.positionAt(0).endsWith("_right"))
        clock.tick()
        if (clock.showsWeather) clock.updateWeather()
    }

    /** To another wallpaper of the set and back; to black and back while there's no set yet. */
    private fun showTransition() {
        stopTransition()
        val image = previewImage ?: return
        if (image.width == 0 || image.drawable == null) return
        val run = demo
        val context = requireContext().applicationContext
        val width = image.width
        background({ SofascreenView.anyWallpaper(context, width) }) { result ->
            if (run != demo) return@background
            backdrop = View(requireContext()).also {
                it.setBackgroundColor(Color.BLACK)
                (image.parent as FrameLayout).addView(it, 0, FrameLayout.LayoutParams(image.width, image.height))
            }
            val first = layer(image).apply { setImageBitmap(result.getOrNull()) }
            SofascreenView.playTransition(first, image, SofascreenView.TRANSITION_MILLIS, width.toFloat(), Runnable {
                SofascreenView.reset(image)
                image.alpha = 0f // under the layers: the way back must not fade through it
                hold = Runnable {
                    val back = layer(image).apply { setImageDrawable(image.drawable) }
                    SofascreenView.playTransition(back, first, SofascreenView.TRANSITION_MILLIS, width.toFloat(), Runnable { stopTransition() })
                }.also { handler.postDelayed(it, TRANSITION_HOLD_MILLIS) }
            })
        }
    }

    /** A black image on top of the preview and its other layers, under the clock. */
    private fun layer(image: ImageView) = ImageView(requireContext()).also {
        it.scaleType = image.scaleType
        it.setBackgroundColor(Color.BLACK)
        it.foreground = image.foreground
        val frame = image.parent as FrameLayout
        frame.addView(it, frame.indexOfChild(layers.lastOrNull() ?: image) + 1, FrameLayout.LayoutParams(image.width, image.height))
        layers += it
    }

    private fun stopTransition() {
        demo++
        hold?.let(handler::removeCallbacks)
        hold = null
        for (layer in layers) {
            layer.animate().cancel()
            (layer.parent as? FrameLayout)?.removeView(layer)
        }
        layers.clear()
        backdrop?.let { (it.parent as? FrameLayout)?.removeView(it) }
        backdrop = null
        previewImage?.let(SofascreenView::reset)
    }

    override fun refresh() {
        super.refresh()
        showClock()
    }

    override fun refreshStatus() {
        clock?.tick()
        updateDescription(ID_START, sofascreenStatus())
        findActionById(ID_START)?.let {
            val enabled = canStart()
            if (it.isEnabled != enabled) {
                it.isEnabled = enabled
                notifyActionChanged(findActionPositionById(ID_START))
            }
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.screensaver_title),
        getString(R.string.screensaver_description),
        getString(R.string.plugin_name),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
        // The remote's help shows on the left while it's focused
        explanations[ID_START] = getString(R.string.screensaver_try_desc) +
            if (ScreensaverSetupFragment.canSwitchOn(requireContext())) "" else "\n\n" + getString(if (ScreensaverSetupFragment.oldFireOs(requireContext())) R.string.screensaver_system_fire_tv_old else R.string.screensaver_system_fire_tv)
        when (ScreensaverSetupFragment.route(requireContext())) {
            ScreensaverSetupFragment.Route.PICKER -> R.string.screensaver_system_explain
            ScreensaverSetupFragment.Route.SETTINGS -> R.string.screensaver_system_explain_path
            ScreensaverSetupFragment.Route.COMPUTER -> R.string.screensaver_system_explain_steps
            ScreensaverSetupFragment.Route.BY_HAND -> null // its row already says it all
        }?.let { explanations[ID_SYSTEM] = getString(it) }
        // Projectivy has a setting of its own that can hold the screensaver back
        if (Projectivy.isInstalled(requireContext()) && ID_SYSTEM in explanations) {
            explanations[ID_SYSTEM] = "${explanations[ID_SYSTEM]}\n\n${getString(R.string.projectivy_screensaver_desc)}"
        }
        explanations[ID_POSITION] = getString(R.string.screensaver_clock_explain)
        explanations[ID_WEATHER] = getString(R.string.screensaver_weather_explain)
        explanations[ID_LOCATION] = getString(R.string.screensaver_weather_explain)
    }

    override fun rows(): List<GuidedAction> {
        val context = requireContext()
        val clockOn = Settings.screensaverClock != Settings.CLOCK_OFF
        return listOfNotNull(
            // Says what it would show
            action(ID_START, R.drawable.ic_act_play, getString(R.string.sofascreen_start), sofascreenStatus())
                .multilineDescription(true).enabled(canStart()).build(),
            action(ID_SYSTEM, R.drawable.ic_act_tv, getString(R.string.sofascreen_switch_on), ScreensaverSetupFragment.status(context))
                .multilineDescription(true).enabled(ScreensaverSetupFragment.canSwitchOn(context)).build()
                // Only says something where it can't be switched on if it was, from a computer
                .takeIf { ScreensaverSetupFragment.canSwitchOn(context) || ScreensaverSetupFragment.isActive(context) == true },

        ) + setRows() + listOfNotNull(
            header(ID_HEADER_SLIDESHOW, getString(R.string.screensaver_slideshow_header)),
            choice(ID_SECONDS, R.drawable.ic_act_schedule, R.string.screensaver_seconds_title, secondsLabel(context, Settings.screensaverSeconds),
                Settings.SCREENSAVER_SECONDS_OPTIONS.map { secondsLabel(context, it) }),
            choice(ID_TRANSITION, R.drawable.ic_act_visibility, R.string.screensaver_transition_title,
                transitionLabel(context, Settings.screensaverTransition), Settings.TRANSITIONS.map { transitionLabel(context, it) }),
            action(ID_ZOOM, R.drawable.ic_act_search, getString(R.string.screensaver_zoom_title), getString(R.string.screensaver_zoom_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverZoom).build(),
            choice(ID_BRIGHTNESS, R.drawable.ic_act_brightness, R.string.screensaver_brightness_title,
                getString(R.string.screensaver_brightness, Settings.screensaverBrightness),
                Settings.SCREENSAVER_BRIGHTNESS_OPTIONS.map { getString(R.string.screensaver_brightness, it) }),

            header(ID_HEADER_CLOCK, getString(R.string.screensaver_clock_header)),
            choice(ID_CLOCK, R.drawable.ic_act_schedule, R.string.screensaver_clock_title, clockLabel(context, Settings.screensaverClock),
                Settings.CLOCK_STYLES.map { clockLabel(context, it) }),
            action(ID_DATE, R.drawable.ic_act_schedule, getString(R.string.screensaver_date_title), null)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverDate).enabled(clockOn).build(),
            choice(ID_FORMAT, R.drawable.ic_act_schedule, R.string.screensaver_clock_format_title, formatLabel(context, Settings.screensaverClockFormat),
                Settings.CLOCK_FORMATS.map { formatLabel(context, it) }).also { it.isEnabled = clockOn },
            choice(ID_POSITION, R.drawable.ic_act_place, R.string.screensaver_clock_position_title, positionLabel(context, Settings.screensaverClockBottom),
                listOf(false, true).map { positionLabel(context, it) }).also { it.isEnabled = clockOn },
            colorRow(clockOn),
            action(ID_WEATHER, R.drawable.ic_act_weather, getString(R.string.screensaver_weather_title),
                Settings.weatherPlace.ifEmpty { getString(R.string.screensaver_weather_no_place) })
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(Settings.screensaverWeather)
                .enabled(clockOn && Settings.weatherPlace.isNotEmpty()).build(),
            editable(ID_LOCATION, R.drawable.ic_act_search, getString(R.string.screensaver_location_title), Settings.weatherPlace,
                Settings.weatherPlace.ifEmpty { getString(R.string.not_set) }).also { it.isEnabled = clockOn },
        )
    }

    /** The clock's colours, or what unlocks them; none in GitHub builds (see [Settings.screensaverClockColorShown]). */
    private fun colorRow(clockOn: Boolean): GuidedAction? {
        if (!Settings.CLOCK_COLORS_OFFERED) return null
        val context = requireContext()
        if (colorsLocked) {
            val pending = supporterState == Supporter.State.PENDING
            return action(ID_COLOR, R.drawable.ic_act_visibility, getString(R.string.screensaver_clock_color_title),
                getString(if (pending) R.string.supporter_pending else R.string.clock_color_locked))
                .multilineDescription(true).enabled(clockOn).build()
        }
        return choice(ID_COLOR, R.drawable.ic_act_visibility, R.string.screensaver_clock_color_title,
            colorLabel(context, Settings.screensaverClockColor), Settings.CLOCK_COLORS.keys.map { colorLabel(context, it) })
            .also { it.isEnabled = clockOn }
    }

    /** Sofascreen makes its set when it starts: only without lists there would be nothing to wait for. */
    private fun canStart(): Boolean {
        val set = Profile.SCREENSAVER.source
        return Sources.selected(set).isNotEmpty() || WallpaperStore(requireContext(), set).current().isNotEmpty()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_START -> startActivity(Intent(requireContext(), SofascreenActivity::class.java))
            ID_SYSTEM -> ScreensaverSetupFragment.open(this)
            ID_ZOOM -> Settings.screensaverZoom = action.isChecked
            ID_DATE -> {
                Settings.screensaverDate = action.isChecked
                showClock()
            }
            ID_WEATHER -> {
                Settings.screensaverWeather = action.isChecked
                showClock()
            }
            ID_COLOR -> if (colorsLocked && supporterState != Supporter.State.PENDING) supporter?.buy(requireActivity())
            else -> super.onGuidedActionClicked(action)
        }
    }

    /** A place typed for the weather: looked up, and the weather switched on when found; empty removes it. */
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id != ID_LOCATION) return GuidedAction.ACTION_ID_CURRENT
        val typed = action.editDescription?.toString().orEmpty().trim()
        if (typed == Settings.weatherPlace) return GuidedAction.ACTION_ID_CURRENT
        if (typed.isEmpty()) {
            Settings.setWeatherPlace(null)
            Settings.screensaverWeather = false
            refresh()
            return GuidedAction.ACTION_ID_CURRENT
        }
        background({ Weather.find(typed) }) { result ->
            val place = result.getOrNull()
            val message = when {
                result.isFailure -> getString(R.string.screensaver_location_failed, result.exceptionOrNull()?.message)
                place == null -> getString(R.string.screensaver_location_not_found, typed)
                else -> place.name.also {
                    Settings.setWeatherPlace(place)
                    Settings.screensaverWeather = true
                }
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            refresh()
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val index = (action.id % SUB_ID_BASE).toInt()
        when (action.id / SUB_ID_BASE) {
            ID_SECONDS -> Settings.screensaverSeconds = Settings.SCREENSAVER_SECONDS_OPTIONS[index]
            ID_TRANSITION -> {
                Settings.screensaverTransition = Settings.TRANSITIONS[index]
                showTransition()
            }
            ID_CLOCK -> Settings.screensaverClock = Settings.CLOCK_STYLES[index]
            ID_POSITION -> Settings.screensaverClockBottom = index == 1
            ID_FORMAT -> Settings.screensaverClockFormat = Settings.CLOCK_FORMATS[index]
            ID_COLOR -> Settings.screensaverClockColor = Settings.CLOCK_COLORS.keys.elementAt(index)
            ID_BRIGHTNESS -> Settings.screensaverBrightness = Settings.SCREENSAVER_BRIGHTNESS_OPTIONS[index]
            else -> return super.onSubGuidedActionClicked(action)
        }
        refresh() // the clock's rows follow the clock
        return true
    }

    companion object {
        private const val ID_START = 1L
        private const val ID_SYSTEM = 2L
        private const val ID_HEADER_SLIDESHOW = 9L
        private const val ID_SECONDS = 10L
        private const val ID_TRANSITION = 11L
        private const val ID_ZOOM = 12L
        private const val ID_BRIGHTNESS = 14L
        private const val ID_HEADER_CLOCK = 15L
        private const val ID_CLOCK = 16L
        private const val ID_DATE = 17L
        private const val ID_FORMAT = 18L
        private const val ID_POSITION = 19L
        private const val ID_WEATHER = 20L
        private const val ID_LOCATION = 21L
        private const val ID_COLOR = 22L
        private const val TRANSITION_HOLD_MILLIS = 1500L

        /** "com.rfmarcos.sofadrop/com.rfmarcos.sofadrop.SofascreenService", for the adb command. */
        fun component(context: Context) = "${context.packageName}/${SofascreenService::class.java.name}"

        fun secondsLabel(context: Context, seconds: Int) =
            if (seconds < 60) context.getString(R.string.screensaver_seconds, seconds)
            else context.getString(R.string.screensaver_minutes, seconds / 60)

        fun transitionLabel(context: Context, transition: String) = context.getString(
            when (transition) {
                Settings.TRANSITION_BLACK -> R.string.transition_black
                Settings.TRANSITION_SLIDE -> R.string.transition_slide
                Settings.TRANSITION_ZOOM -> R.string.transition_zoom
                Settings.TRANSITION_RANDOM -> R.string.transition_random
                else -> R.string.transition_fade
            }
        )

        fun clockLabel(context: Context, style: String) = context.getString(
            when (style) {
                Settings.CLOCK_STACKED -> R.string.clock_stacked
                Settings.CLOCK_LINE -> R.string.clock_line
                else -> R.string.clock_off
            }
        )

        /** "System (24 hours)", "24 hours", "12 hours". */
        private fun formatLabel(context: Context, format: String) = when (format) {
            Settings.CLOCK_FORMAT_24 -> context.getString(R.string.clock_format_24)
            Settings.CLOCK_FORMAT_12 -> context.getString(R.string.clock_format_12)
            else -> context.getString(R.string.clock_format_system, context.getString(
                if (android.text.format.DateFormat.is24HourFormat(context)) R.string.clock_format_24 else R.string.clock_format_12))
        }

        private fun colorLabel(context: Context, color: String) = context.getString(
            when (color) {
                Settings.CLOCK_COLOR_AMBER -> R.string.clock_color_amber
                Settings.CLOCK_COLOR_BLUE -> R.string.clock_color_blue
                Settings.CLOCK_COLOR_PINK -> R.string.clock_color_pink
                Settings.CLOCK_COLOR_TURQUOISE -> R.string.clock_color_turquoise
                else -> R.string.clock_color_white
            }
        )

        private fun positionLabel(context: Context, bottom: Boolean) =
            context.getString(if (bottom) R.string.clock_bottom else R.string.clock_top)

        /** "On · Own lists · 30 s · clock" for the main screen. */
        fun summary(context: Context): String = listOfNotNull(
            when (ScreensaverSetupFragment.isActive(context)) {
                true -> R.string.screensaver_summary_on
                // Where it takes a computer, starting it by hand isn't a fault
                false -> if (ScreensaverSetupFragment.startsByHand(context)) R.string.screensaver_summary_manual else R.string.screensaver_system_off
                null -> null
            }?.let(context::getString),
            ownSummary(context, Profile.SCREENSAVER),
            secondsLabel(context, Settings.screensaverSeconds),
            context.getString(R.string.screensaver_summary_clock).takeIf { Settings.screensaverClock != Settings.CLOCK_OFF },
            context.getString(R.string.screensaver_summary_weather).takeIf {
                Settings.screensaverClock != Settings.CLOCK_OFF && Settings.screensaverWeather && Settings.weatherPlace.isNotEmpty()
            },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

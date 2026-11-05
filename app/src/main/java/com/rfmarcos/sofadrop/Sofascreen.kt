package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sofascreen: the screensaver's set (its own, or the wallpapers', see [Profile.SCREENSAVER]) shown offline,
 * with a transition between wallpapers, an optional slow zoom and an optional clock with the date and the
 * weather (see the screensaver settings in [Settings]). A set published while it runs is picked up at the
 * end of a round, and a first one as soon as it has a wallpaper. Contributed by schumi2004 (PR #13).
 */
class SofascreenView(context: Context) : FrameLayout(context) {

    private val store = WallpaperStore(context, Profile.SCREENSAVER)
    /** Where the wallpapers' text is, so the zoom keeps it in view and the clock goes on the other side. */
    private val blockPosition = Profile.SCREENSAVER.position.takeIf { it != Settings.POSITION_ROTATE } ?: Settings.POSITION_TOP_LEFT
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private var front = image()
    private var back = image()
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val clock = SofascreenClock(context)
    private val empty = TextView(context).apply {
        setText(R.string.screensaver_empty)
        setTextColor(Color.argb(200, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        visibility = GONE
    }

    private var playlist: List<StoredWallpaper> = emptyList()
    private var position = -1
    /** The wallpaper on screen, for [openShown]. */
    private var shown: StoredWallpaper? = null
    /** Ends the running transition at once, as an arrow may arrive during one; null when there is none. */
    private var settle: Runnable? = null
    private var running = false
    private val next = Runnable { showNext() }
    private val drift = object : Runnable {
        override fun run() {
            // A few pixels every minute, so the clock doesn't burn into the panel
            val range = resources.displayMetrics.heightPixels * 0.012f
            clock.view.animate().translationX(Random.nextFloat() * 2 * range - range).translationY(Random.nextFloat() * 2 * range - range)
                .setDuration(2000).start()
            handler.postDelayed(this, 60_000)
        }
    }
    /** The time and date, at the start of every minute. */
    private val tick = object : Runnable {
        override fun run() {
            clock.tick()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    private val weatherUpdate = object : Runnable {
        override fun run() {
            clock.updateWeather()
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clock.view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    fun start() {
        if (running) return
        running = true
        dim.alpha = 1f - Settings.screensaverBrightness / 100f
        clock.build(resources.displayMetrics.widthPixels.toFloat(), resources.displayMetrics.heightPixels.toFloat(), blockPosition.endsWith("_right"))
        position = -1
        playlist = emptyList()
        showNext(transition = 0)
        if (Settings.screensaverClock != Settings.CLOCK_OFF) {
            tick.run()
            handler.postDelayed(drift, 60_000)
        }
        if (clock.showsWeather) handler.post(weatherUpdate)
    }

    fun stop() {
        running = false
        shown = null
        settle = null
        handler.removeCallbacksAndMessages(null)
        for (view in listOf(front, back, clock.view)) view.animate().cancel()
        front.setImageDrawable(null)
        back.setImageDrawable(null)
    }

    override fun onDetachedFromWindow() {
        stop()
        decoder.shutdownNow()
        clock.close()
        super.onDetachedFromWindow()
    }

    /**
     * The remote: the arrows change the wallpaper, OK opens the title on screen, any other button stops
     * Sofascreen. True when it goes on. Held arrows don't repeat: each step decodes a whole image.
     */
    fun onKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> true.also { if (event.repeatCount == 0) skip(forward = true) }
        KeyEvent.KEYCODE_DPAD_LEFT -> true.also { if (event.repeatCount == 0) skip(forward = false) }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> false.also { openShown() }
        else -> false
    }

    /** The title on screen, as Projectivy would open it (see [ClickTargets]). */
    private fun openShown() {
        val wallpaper = shown ?: return
        ClickTargets.candidates(context, wallpaper, Profile.SCREENSAVER).any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
    }

    /** Straight to the next or the previous wallpaper of the round, with a quicker transition. */
    private fun skip(forward: Boolean) {
        if (playlist.isEmpty()) return
        handler.removeCallbacks(next)
        if (!forward) position = (position - 1 + playlist.size) % playlist.size - 1
        showNext(SKIP_MILLIS)
    }

    // --- Wallpapers ---

    /** The next wallpaper; a new round re-reads the set, so a newly published one is used. */
    private fun showNext(transition: Long = TRANSITION_MILLIS) {
        if (!running) return
        if (position + 1 >= playlist.size) {
            val current = store.current()
            playlist = if (Profile.SCREENSAVER.mixed) current.shuffled() else current
            position = -1
        }
        if (playlist.isEmpty()) {
            val set = Profile.SCREENSAVER.source
            empty.setText(
                when {
                    set.generating -> if (GenerateWallpapersWorker.isOnline(context)) R.string.screensaver_preparing else R.string.status_waiting_network
                    set.lastError.isNotEmpty() -> R.string.screensaver_failed
                    else -> R.string.screensaver_empty
                }
            )
            empty.visibility = VISIBLE
            // Nothing generated yet: look again later, soon if it's on its way
            handler.postDelayed(next, if (Profile.SCREENSAVER.source.generating) 2_000L else 10_000L)
            return
        }
        empty.visibility = GONE
        position++
        val wallpaper = playlist[position]
        val file = store.fileOf(wallpaper)
        val width = resources.displayMetrics.widthPixels
        decoder.execute {
            val exists = file?.isFile == true
            // Errors too (out of memory): a wallpaper that can't be read mustn't stop the screensaver
            val bitmap = if (exists) runCatching { decode(file!!.path, width) }.getOrNull() else null
            handler.post {
                if (!running) return@post
                when {
                    bitmap != null -> {
                        shown = wallpaper
                        show(bitmap, transition)
                    }
                    // Removed by a newer set: start the round again from that one
                    !exists -> {
                        playlist = emptyList()
                        handler.postDelayed(next, 500)
                    }
                    // Damaged, or too big for the memory left: the next one
                    else -> handler.postDelayed(next, 500)
                }
            }
        }
    }

    private fun show(bitmap: Bitmap, duration: Long) {
        settle?.run()
        val incoming = back
        val outgoing = front
        reset(incoming)
        incoming.setImageBitmap(bitmap)
        // The incoming image goes on top of the outgoing one, under the dimming and the clock
        removeView(incoming)
        addView(incoming, indexOfChild(outgoing) + 1)
        back = outgoing
        front = incoming

        val done = Runnable {
            settle = null
            reset(incoming)
            reset(outgoing)
            outgoing.setImageDrawable(null)
            slowZoom(incoming)
        }
        settle = done
        shown?.position?.let(clock::moveAwayFrom)
        if (duration == 0L) done.run() else playTransition(incoming, outgoing, duration, width.toFloat(), done)
        handler.removeCallbacks(next)
        handler.postDelayed(next, Settings.screensaverSeconds * 1000L + duration)
    }

    /**
     * Zoom in slowly while the wallpaper is shown, anchored at the side and height of its text (see
     * [Settings.POSITIONS]) so its logo and text stay in view.
     */
    private fun slowZoom(view: ImageView) {
        if (!Settings.screensaverZoom) return
        val screen = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val place = shown?.position ?: blockPosition
        view.pivotX = if (place.endsWith("_right")) (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat() else 0f
        view.pivotY = when {
            place.startsWith("bottom") -> screen
            place.startsWith("top") -> 0f
            else -> screen / 2f
        }
        view.animate().scaleX(SLOW_ZOOM).scaleY(SLOW_ZOOM).setDuration(Settings.screensaverSeconds * 1000L)
            .setInterpolator(LinearInterpolator()).start()
    }

    companion object {
        const val TRANSITION_MILLIS = 1500L
        private const val SKIP_MILLIS = 500L
        private const val SLOW_ZOOM = 1.06f

        /**
         * One change from [outgoing] to [incoming], which is on top of it, with the transition of the settings.
         * [distance] is how far a slide goes.
         */
        fun playTransition(incoming: ImageView, outgoing: ImageView, duration: Long, distance: Float, done: Runnable) {
            val transition = Settings.screensaverTransition.let {
                if (it == Settings.TRANSITION_RANDOM) Settings.TRANSITIONS.filter { t -> t != Settings.TRANSITION_RANDOM }.random() else it
            }
            val ease = AccelerateDecelerateInterpolator()
            when (transition) {
                Settings.TRANSITION_BLACK -> {
                    incoming.alpha = 0f
                    outgoing.animate().alpha(0f).setDuration(duration / 2).setInterpolator(ease).withEndAction {
                        incoming.animate().alpha(1f).setDuration(duration / 2).setInterpolator(ease).withEndAction(done).start()
                    }.start()
                }
                Settings.TRANSITION_SLIDE -> {
                    incoming.translationX = distance
                    outgoing.animate().translationX(-distance).setDuration(duration).setInterpolator(ease).start()
                    incoming.animate().translationX(0f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
                }
                Settings.TRANSITION_ZOOM -> {
                    incoming.alpha = 0f
                    incoming.scaleX = 1.15f
                    incoming.scaleY = 1.15f
                    incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
                }
                else -> {
                    incoming.alpha = 0f
                    incoming.animate().alpha(1f).setDuration(duration).setInterpolator(ease).withEndAction(done).start()
                }
            }
        }

        fun reset(view: ImageView) {
            view.animate().cancel()
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }

        /** Some wallpaper of the set, for the preview in the settings. */
        fun anyWallpaper(context: Context, width: Int): Bitmap? {
            val store = WallpaperStore(context, Profile.SCREENSAVER)
            return store.current().randomOrNull()?.let(store::fileOf)?.let { decode(it.path, width) }
        }

        /** Decode an image subsampled close to [width], like the wallpaper viewer. */
        private fun decode(path: String, width: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/**
 * The clock block with the date and the weather, as the screensaver settings say, in a [FrameLayout]: on the
 * wallpaper in [SofascreenView], and on the settings' preview.
 */
private const val CLOCK_MOVE_MILLIS = 300L

class SofascreenClock(private val context: Context) {

    private val clock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private var date: TextView? = null
    /** The clock's digits and their patterns ("HH", "mm"), set by [tick]. */
    private val times = mutableListOf<Pair<TextView, String>>()
    private var weather: TextView? = null

    val view: View get() = clock
    val showsWeather: Boolean get() = weather != null

    /** The time and date now. */
    fun tick() {
        val now = Date()
        for ((view, pattern) in times) view.text = DateFormat.format(pattern, now)
        date?.text = dateText()
    }

    fun close() {
        network.shutdownNow()
    }

    /** Sized for a screen of [width] by [height]; it goes on the side the wallpapers' text isn't. */
    fun build(width: Float, height: Float, textRight: Boolean) {
        val side = if (textRight) Gravity.START else Gravity.END
        clock.removeAllViews()
        times.clear()
        date = null
        weather = null
        val style = Settings.screensaverClock
        clock.visibility = if (style == Settings.CLOCK_OFF) View.GONE else View.VISIBLE
        if (style == Settings.CLOCK_OFF) return
        val h24 = when (Settings.screensaverClockFormat) {
            Settings.CLOCK_FORMAT_24 -> true
            Settings.CLOCK_FORMAT_12 -> false
            else -> DateFormat.is24HourFormat(context)
        }
        val digits = ResourcesCompat.getFont(context, R.font.roboto_bold)
        val light = ResourcesCompat.getFont(context, R.font.roboto_light)
        val small = height * 0.045f

        if (style == Settings.CLOCK_STACKED) {
            // Hours, the date, minutes: one block that reads at a glance
            clock.gravity = Gravity.CENTER_HORIZONTAL
            clock.addView(clockText(if (h24) "HH" else "h", height * 0.17f, digits))
            if (Settings.screensaverDate) date = smallText(small, light).also { clock.addView(it) }
            else clock.addView(divider(height), LinearLayout.LayoutParams((height * 0.13f).toInt(), (height * 0.004f).toInt().coerceAtLeast(2)).apply {
                // Without the date: a thin line between hours and minutes
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (height * 0.022f).toInt()
                bottomMargin = (height * 0.026f).toInt()
            })
            clock.addView(clockText("mm", height * 0.17f, digits))
        } else {
            clock.gravity = side
            clock.addView(clockText(if (h24) "HH:mm" else "h:mm", height * 0.11f, digits))
            if (Settings.screensaverDate) date = smallText(small, light).also { clock.addView(it) }
        }
        if (Settings.screensaverWeather && Settings.weatherPlace.isNotEmpty()) {
            weather = smallText(small * 0.85f, light).also {
                it.visibility = View.GONE // until there is a reading
                it.setPadding(0, (small * 0.4f).toInt(), 0, 0)
                clock.addView(it)
            }
        }

        val params = clock.layoutParams as FrameLayout.LayoutParams
        params.gravity = side or (if (Settings.screensaverClockBottom) Gravity.BOTTOM else Gravity.TOP)
        params.marginEnd = (width * 0.055f).toInt()
        params.marginStart = params.marginEnd
        params.topMargin = (height * 0.07f).toInt()
        params.bottomMargin = (height * 0.07f).toInt()
        clock.layoutParams = params
    }

    /** To the corner away from text at [position], for wallpapers that each have theirs (the height stays with text in the middle). */
    fun moveAwayFrom(position: String) {
        val side = if (position.endsWith("_right")) Gravity.START else Gravity.END
        val params = clock.layoutParams as FrameLayout.LayoutParams
        val height = when {
            position.startsWith("top") -> Gravity.BOTTOM
            position.startsWith("bottom") -> Gravity.TOP
            else -> params.gravity and Gravity.VERTICAL_GRAVITY_MASK
        }
        if (params.gravity == side or height) return
        clock.animate().alpha(0f).setDuration(CLOCK_MOVE_MILLIS).withEndAction {
            params.gravity = side or height
            clock.layoutParams = params
            if (Settings.screensaverClock == Settings.CLOCK_LINE) clock.gravity = side
            clock.animate().alpha(1f).setDuration(CLOCK_MOVE_MILLIS).start()
        }.start()
    }

    private fun clockText(pattern: String, sizePx: Float, font: android.graphics.Typeface?) = TextView(context).apply {
        style(this, sizePx, font)
        maxLines = 1
        times += this to pattern
    }

    /** Rounded, slightly see-through white, like the light date it replaces. */
    private fun divider(height: Float) = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(190, 255, 255, 255))
            cornerRadius = height * 0.002f
        }
    }

    /** The date and weather lines: thin capitals, spaced a little. */
    private fun smallText(sizePx: Float, font: android.graphics.Typeface?) = TextView(context).apply {
        style(this, sizePx, font)
        setAllCaps(true)
        letterSpacing = 0.04f
        // Thin and small: a stronger shadow than the digits' keeps it readable on a light wallpaper
        setShadowLayer(sizePx * 0.25f, 0f, sizePx * 0.04f, Color.argb(220, 0, 0, 0))
    }

    private fun style(view: TextView, sizePx: Float, font: android.graphics.Typeface?) = view.apply {
        // Its own width: by default a vertical LinearLayout makes it as wide as the block, which then
        // keeps the width it had before the text was set, cutting "12" down to "1"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        setShadowLayer(sizePx * 0.08f, 0f, sizePx * 0.02f, Color.argb(150, 0, 0, 0))
    }

    /** "MA·26·OKT", "MON·OCT·26": weekday, day and month in the order of the language, without their dots. */
    private fun dateText(): String {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM").replace(Regex("'[^']*'"), "")
        val now = Date()
        return Regex("E+|c+|d+|M+|L+").findAll(pattern)
            .joinToString("·") { SimpleDateFormat(it.value, locale).format(now).replace(".", "").trim() }
    }

    /** "12° · Partly cloudy" under the clock, off the main thread; the line stays hidden while there is none. */
    fun updateWeather() {
        val place = Settings.weatherPlace.takeIf { it.isNotEmpty() } ?: return
        network.execute {
            // A restored backup has the place but not its coordinates: look it up again
            val coordinates = Settings.weatherCoordinates ?: runCatching { Weather.find(place.substringBefore(',')) }.getOrNull()
                ?.also { Settings.setWeatherPlace(it.copy(name = place)) }?.let { it.latitude to it.longitude }
            val now = coordinates?.let { Weather.current(it) }
            handler.post {
                val line = weather ?: return@post
                line.visibility = if (now == null) View.GONE else View.VISIBLE
                if (now != null) line.text = "${now.temperature}°  ·  ${context.getString(Weather.description(now.code))}"
            }
        }
    }
}

/** The system screensaver ("Sofascreen" in Android's screensaver settings). */
class SofascreenService : DreamService() {

    private var view: SofascreenView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Settings.init(this)
        isInteractive = true // for the remote, see [dispatchKeyEvent]; any other input stops it, as without this
        isFullscreen = true
        isScreenBright = true
        view = SofascreenView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        GenerateWallpapersWorker.refreshForScreensaver(this)
        view?.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back closes on key up: closing now, its key up would go on to what's underneath
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finish()
        } else if (event.action == KeyEvent.ACTION_DOWN && view?.onKey(event) != true) finish()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        finish()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        finish()
        return true
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }
}

/**
 * Sofascreen on demand, from the settings: the way in where the system screensaver takes a computer (see
 * [ScreensaverSetupFragment]). Exported so launchers can start it when idle, which they can't do with
 * another app's [SofascreenService]. It keeps the screen on until a key closes it. The remote works as
 * with the real screensaver, see [SofascreenView.onKey].
 */
class SofascreenActivity : AppCompatActivity() {

    private lateinit var view: SofascreenView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = SofascreenView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        GenerateWallpapersWorker.refreshForScreensaver(this)
        view.start()
    }

    override fun onStop() {
        view.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back closes on key up as usual: closing now, its key up would go back in the settings too
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (!view.onKey(event)) finish()
        return true
    }
}

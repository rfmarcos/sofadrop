package com.rfmarcos.sofadrop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rfmarcos.sofadrop.BaseSettingsFragment.Companion.forProfile
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The TV screenshots of the Google Play listing, 1920x1080 without the system bars, into the app's external files
 * (play/).
 * Run with `am instrument` on the emulator, as it replaces the set and changes settings.
 */
@RunWith(AndroidJUnit4::class)
class PlayImagesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.getExternalFilesDir(null), "play").apply { deleteRecursively(); mkdirs() }

    @Test
    fun takesStoreScreenshots() {
        Settings.init(context)
        Settings.wizardShown = true
        Settings.language = "en-US"
        val look = Profile.WALLPAPER
        look.style = Settings.STYLE_FULL
        look.showCast = false
        look.showProviders = false
        look.showSource = false
        look.ratingSources = listOf(RatingSource.TMDB)
        look.overviewLines = 4
        look.position = Settings.POSITION_TOP_LEFT

        val onlyTheFirst = publish(look)
        Settings.pinPreview("${TITLES.first().type.path}:${TITLES.first().tmdbId}", "")

        Settings.screensaverClockColor = Settings.CLOCK_COLOR_AMBER
        Settings.screensaverSeconds = 30
        Settings.screensaverZoom = false
        val settings = open(SettingsActivity::class.java)
        settle()
        shot(settings, "settings")

        showScreen(settings, ScreensaverFragment().forProfile(Profile.SCREENSAVER))
        select(settings, context.getString(R.string.screensaver_clock_color_title))
        shot(settings, "screensaver-settings")

        showScreen(settings, ListsFragment().forProfile(Profile.GENERAL))
        shot(settings, "lists")
        settings.finish()

        onlyTheFirst()
        val screensaver = open(SofascreenActivity::class.java)
        Thread.sleep(SCREENSAVER_MILLIS)
        shot(screensaver, "sofascreen-amber")
        screensaver.finish()

        Settings.screensaverClockColor = Settings.CLOCK_COLOR_WHITE
        Thread.sleep(SCREENSAVER_MILLIS) // the previous one closing
        val white = open(SofascreenActivity::class.java)
        Thread.sleep(SCREENSAVER_MILLIS)
        shot(white, "sofascreen-white")
        white.finish()
    }

    /** The wallpapers, as made by the app, become the current set and files of their own. */
    private fun publish(look: Profile): () -> Unit {
        val factory = WallpaperFactory(context, 1920, look)
        val store = WallpaperStore(context, look)
        val generation = store.newGeneration()
        val stored = TITLES.mapNotNull { ref ->
            val info = factory.build(ref) ?: return@mapNotNull null
            val bitmap = factory.render(info)
            val name = "${info.type.path}-${info.id}.jpg"
            File(generation, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            File(dir, "wallpaper-${info.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            StoredWallpaper(name, info.title, info.type.path, info.id)
        }
        store.publish(generation, stored)
        // Not stale: Sofascreen would make a new set from the lists
        look.lastGeneration = System.currentTimeMillis()
        return { store.publish(generation, stored.take(1)) }
    }

    private fun <T : Activity> open(type: Class<T>): T {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("UNCHECKED_CAST")
        val activity = instrumentation.startActivitySync(intent) as T
        instrumentation.runOnMainSync {
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.decorView.windowInsetsController?.hide(WindowInsets.Type.systemBars())
        }
        return activity
    }

    private fun showScreen(activity: FragmentActivity, screen: GuidedStepSupportFragment) {
        instrumentation.runOnMainSync { GuidedStepSupportFragment.add(activity.supportFragmentManager, screen) }
        settle()
    }

    private fun select(activity: FragmentActivity, title: String) {
        instrumentation.runOnMainSync {
            val screen = GuidedStepSupportFragment.getCurrentGuidedStepSupportFragment(activity.supportFragmentManager)!!
            screen.selectedActionPosition = screen.actions.indexOfFirst { it.title == title }
        }
        settle()
    }

    /** For the previews to arrive, which come from the network. */
    private fun settle() = Thread.sleep(SETTLE_MILLIS)

    private fun shot(activity: Activity, name: String) {
        instrumentation.runOnMainSync { dropDevName(activity.window.decorView) }
        Thread.sleep(300)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot")
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Debug builds call themselves "Sofadrop Dev": the listing shows the app's name. */
    private fun dropDevName(view: View) {
        when (view) {
            is TextView -> if (view.text?.toString() == DEV_NAME) view.text = NAME
            is ViewGroup -> for (index in 0 until view.childCount) dropDevName(view.getChildAt(index))
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 7000L
        const val SCREENSAVER_MILLIS = 3000L
        const val DEV_NAME = "Sofadrop Dev"
        const val NAME = "Sofadrop"

        /**
         * Popular titles with a logo, as other movie apps show on Google Play. If a review objects, the same
         * screenshots with public domain films and open movies: movies 964, 775, 234, 10378 and 9761.
         */
        val TITLES = listOf(
            TitleRef(MediaType.TV, 66732), TitleRef(MediaType.TV, 1396), TitleRef(MediaType.MOVIE, 693134),
            TitleRef(MediaType.MOVIE, 157336), TitleRef(MediaType.TV, 100088),
        )
    }
}

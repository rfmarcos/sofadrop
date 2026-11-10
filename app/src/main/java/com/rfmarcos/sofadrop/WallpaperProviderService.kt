package com.rfmarcos.sofadrop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** The card in focus while [Settings.focusedCard] is on (see [FocusedWallpapers]). */
    @Volatile private var focusedCard: FocusedCard? = null

    /** Projectivy shows [focusedCard]'s wallpaper, which must give way to the set when the focus leaves it. */
    @Volatile private var showingCard = false

    private val binder = object : IWallpaperProviderService.Stub() {

        /**
         * Projectivy asks on start, every few seconds (TimeElapsed) and, as the manifest's updateMode asks,
         * when a card is focused and when it goes idle. An empty answer leaves the wallpaper as it is.
         */
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            Log.d(TAG, "getWallpapers(${event?.javaClass?.simpleName})")
            Settings.projectivyAskedAt = System.currentTimeMillis()
            val mode = Settings.focusedCard
            when (event) {
                is Event.ProgramCardFocused -> {
                    val card = if (mode == Settings.FOCUSED_OFF) null else FocusedWallpapers.card(event.title, event.type, event.iconUri)
                    if (mode != Settings.FOCUSED_OFF && card == null) FocusedLog.ignored(this@WallpaperProviderService, event.title, event.type, event.iconUri)
                    focusedCard = card
                    if (card != null) {
                        cardWallpaper(card, mode)?.let { return it }
                        // Still drawn, or focus moved on: the wallpaper stays. Found nothing: as with any other card
                        if (focusedCard !== card || !FocusedWallpapers.matchesNothing(card)) return emptyList()
                    }
                }
                is Event.CardFocused, is Event.LauncherIdleModeChanged -> focusedCard = null
                is Event.TimeElapsed -> focusedCard?.let { card ->
                    if (mode == Settings.FOCUSED_OFF) focusedCard = null
                    // The same wait as the card's own request: answering the set now would win over it
                    else cardWallpaper(card, mode)?.let { return it }
                }
                else -> return emptyList()
            }
            // Away from the card: its wallpaper gives way to the set; otherwise card events change nothing
            if (event !is Event.TimeElapsed && !showingCard) return emptyList()
            showingCard = false
            return wallpapers()
        }

        /**
         * [card]'s wallpaper, waiting for it to be drawn (Projectivy doesn't wait, but applies it when it
         * comes); null while it's drawn or found nothing, or once the focus moved on (its answer would
         * come after the next card's).
         */
        private fun cardWallpaper(card: FocusedCard, mode: String): List<Wallpaper>? {
            val wallpaper = FocusedWallpapers.wallpaper(this@WallpaperProviderService, card, mode, CARD_WAIT_MILLIS) ?: return null
            if (focusedCard !== card) return null
            showingCard = true
            return listOf(wallpaper)
        }

        private fun wallpapers(): List<Wallpaper> {
            // Never block Projectivy: return what we have and refresh in the background if needed
            val store = WallpaperStore(this@WallpaperProviderService)
            val current = store.current()
            // Settings changes wait for the next scheduled update (or "Update now"): only an empty
            // or expired set is regenerated from here, as a safety net for the periodic job
            val stale = System.currentTimeMillis() - Profile.WALLPAPER.lastGeneration > Settings.refreshIntervalMillis
            if (current.isEmpty() || stale) {
                GenerateWallpapersWorker.runNow(this@WallpaperProviderService)
            }
            GenerateWallpapersWorker.schedule(this@WallpaperProviderService)

            Log.i(TAG, "getWallpapers: ${current.size} wallpapers, stale=$stale, pendingChanges=${Profile.WALLPAPER.hasPendingChanges}")
            store.markServed()
            val apps = ClickTargets.Apps(this@WallpaperProviderService)
            return (if (Profile.WALLPAPER.mixed) current.shuffled() else current).mapNotNull { wallpaper ->
                val uri = store.uriOf(wallpaper) ?: return@mapNotNull null
                Wallpaper(
                    uri = uri.toString(),
                    type = WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = wallpaper.title,
                    source = "https://www.themoviedb.org/${wallpaper.type}/${wallpaper.tmdbId}",
                    author = "TMDB",
                    // "Watch" in Projectivy's "About this wallpaper" and a play button on its status bar
                    actionUri = actionUri(this@WallpaperProviderService, wallpaper, apps),
                )
            }
        }

        override fun getPreferences(): String = Settings.export()

        override fun setPreferences(params: String) {
            Settings.import(params)
        }

    }

    companion object {
        private const val TAG = "SofadropService"
        /** Longer than drawing takes on a Chromecast with Google TV (3-4 s, 10 s just after Sofadrop starts). */
        private const val CARD_WAIT_MILLIS = 8000L

        /**
         * What Projectivy opens from [wallpaper] (see [ClickTargets]); applied when served, without
         * regenerating (except the platforms' pages).
         */
        fun actionUri(context: Context, wallpaper: StoredWallpaper, apps: ClickTargets.Apps = ClickTargets.Apps(context)): String? =
            ClickTargets.candidates(context, wallpaper, Profile.WALLPAPER, apps).firstOrNull()?.toUri(Intent.URI_INTENT_SCHEME)
    }
}

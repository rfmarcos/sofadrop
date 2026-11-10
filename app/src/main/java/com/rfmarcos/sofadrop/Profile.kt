package com.rfmarcos.sofadrop

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The settings of one place the wallpapers show: [WALLPAPER] (Projectivy's home screen) or [SCREENSAVER]. Both
 * use [GENERAL], with the keys every version used, until they're given their own lists, look, click action or
 * order; those are stored apart, with their prefix ("wp_", "ss_"). Accounts, keys, language and blocked titles
 * are shared by all. There are two sets on disk only when lists or look differ (see [twoSets]).
 */
class Profile private constructor(val id: String, private val prefix: String) {

    val isScreensaver: Boolean get() = this === SCREENSAVER
    val isGeneral: Boolean get() = this === GENERAL

    private fun own(name: String) = "${id}_own_$name"
    private fun isOwn(name: String) = !isGeneral && preferences.getBoolean(own(name), false)

    /** Turned on, it starts as the general settings' [keys]. */
    private fun setOwn(name: String, on: Boolean, keys: List<String>) {
        if (isGeneral) return
        preferences.edit {
            if (on && !isOwn(name)) copyGeneral(keys)
            putBoolean(own(name), on)
        }
    }

    private fun SharedPreferences.Editor.copyGeneral(keys: List<String>) {
        val values = preferences.all
        for (key in keys) {
            val copy = prefix + key
            when (val value = values[key]) {
                is String -> putString(copy, value)
                is Boolean -> putBoolean(copy, value)
                is Int -> putInt(copy, value)
                is Long -> putLong(copy, value)
                is Float -> putFloat(copy, value)
                is Set<*> -> putStringSet(copy, value.map { it.toString() }.toSet())
                else -> remove(copy)
            }
        }
    }

    var ownLists: Boolean
        get() = isOwn("lists")
        set(value) = setOwn("lists", value, LISTS_KEYS)

    var ownLook: Boolean
        get() = isOwn("look")
        set(value) = setOwn("look", value, LOOK_KEYS)

    var ownClick: Boolean
        get() = isOwn("click")
        set(value) = setOwn("click", value, CLICK_KEYS)

    /** Its wallpapers aren't the general ones: lists or look of its own. */
    val ownSet: Boolean get() = ownLists || ownLook

    /** The screensaver has a set of its own on disk. */
    val separate: Boolean get() = isScreensaver && twoSets

    /**
     * The profile whose set this one shows. With one set, the wallpapers'. The general settings' is whichever
     * place uses them (the wallpapers' when both or neither do).
     */
    val source: Profile
        get() = when {
            isGeneral -> if (SCREENSAVER.ownSet || !WALLPAPER.ownSet) WALLPAPER else SCREENSAVER
            isScreensaver && !twoSets -> WALLPAPER
            else -> this
        }

    private val preferences get() = Settings.preferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun lists(key: String) = if (ownLists) prefix + key else key
    private fun look(key: String) = if (ownLook) prefix + key else key
    private fun click(key: String) = if (ownClick) prefix + key else key
    // "_last_generation" -> "_ss_last_generation"
    private fun state(key: String) = if ((if (isGeneral) source else this).isScreensaver) "_ss$key" else key

    private fun string(key: String, default: String = "") = preferences.getString(key, null)?.trim().orEmpty().ifEmpty { default }

    // --- Lists and filters ---

    /** Selected list keys, e.g. "tmdb:trending_movies", "trakt:watchlist_shows", "mdblist:1234". */
    var lists: Set<String>
        get() = preferences.getStringSet(lists(KEY_LISTS), null)?.toSet() ?: Settings.DEFAULT_LISTS
        set(value) = preferences.edit { putStringSet(lists(KEY_LISTS), value) }

    var titlesPerList: Int
        get() = preferences.getInt(lists(KEY_COUNT), 20)
        set(value) = preferences.edit { putInt(lists(KEY_COUNT), value) }

    var globalHitsOnly: Boolean
        get() = preferences.getBoolean(lists(KEY_GLOBAL_HITS), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_GLOBAL_HITS), value) }

    /**
     * Skip titles released (or, for TV, last aired) longer ago than this many months, or released
     * before this year (see [Settings.isYear]); 0 = no limit.
     */
    var maxAgeMonths: Int
        get() = preferences.getInt(lists(KEY_MAX_AGE), 0)
        set(value) = preferences.edit { putInt(lists(KEY_MAX_AGE), value) }

    var excludeAdult: Boolean
        get() = BuildConfig.PLAY || preferences.getBoolean(lists(KEY_EXCLUDE_ADULT), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_EXCLUDE_ADULT), value) }

    /** Skip titles certified for older viewers than this, in every list (see [Certifications.minimumAge]); 0 = no limit. */
    var maxRatingAge: Int
        get() = preferences.getInt(lists(KEY_MAX_RATING_AGE), 0)
        set(value) = preferences.edit { putInt(lists(KEY_MAX_RATING_AGE), value) }

    /** Skip titles with a lower TMDB score (titles with too few votes to tell pass); 0 = no limit. */
    var minRating: Double
        get() = preferences.getFloat(lists(KEY_MIN_RATING), 0f).toDouble()
        set(value) = preferences.edit { putFloat(lists(KEY_MIN_RATING), value.toFloat()) }

    /** Default genre filter; like the age, it only applies by default to automatic lists (see [filtersFor]). */
    var genreFilter: GenreFilter
        get() = try {
            json.decodeFromString<GenreFilter>(preferences.getString(lists(KEY_GENRE_FILTER), null) ?: "{}")
        } catch (_: Exception) {
            GenreFilter()
        }
        set(value) = preferences.edit { putString(lists(KEY_GENRE_FILTER), json.encodeToString(value)) }

    /** A title already taken by an earlier list is skipped there, so that list uses its next one. */
    var skipDuplicates: Boolean
        get() = preferences.getBoolean(lists(KEY_SKIP_DUPLICATES), true)
        set(value) = preferences.edit { putBoolean(lists(KEY_SKIP_DUPLICATES), value) }

    /** "order": first titles of each list; "random": random picks among the best ones. */
    var selectionMode: String
        get() = preferences.getString(lists(KEY_SELECTION), Settings.SELECTION_RANDOM) ?: Settings.SELECTION_RANDOM
        set(value) = preferences.edit { putString(lists(KEY_SELECTION), value) }

    /** What to do with titles in the watch history: "show", "mark" or "hide". */
    var watchedMode: String
        get() = preferences.getString(lists(KEY_WATCHED), Settings.WATCHED_MARK) ?: Settings.WATCHED_MARK
        set(value) = preferences.edit { putString(lists(KEY_WATCHED), value) }

    /** Per-list overrides of the default filters (list key -> values), set on each list's page in "Filters & explore lists". */
    var listFilters: Map<String, ListFilters>
        get() = try {
            json.decodeFromString<Map<String, ListFilters>>(preferences.getString(lists(KEY_LIST_FILTERS), null) ?: "{}")
        } catch (_: Exception) {
            emptyMap()
        }
        set(value) = preferences.edit { putString(lists(KEY_LIST_FILTERS), json.encodeToString(value.filterValues { !it.isDefault })) }

    /**
     * Filters a list really uses: its overrides, else the defaults. Age, global hits and genres only apply
     * by default to automatic lists (trending, popular…), so watchlists and favourites aren't emptied.
     */
    fun filtersFor(source: ListSource): FilterValues {
        val custom = listFilters[source.key]
        return FilterValues(
            maxAgeMonths = custom?.maxAge ?: defaultMaxAge(source),
            globalHits = (custom?.globalHits ?: defaultGlobalHits(source)) && source.localMarketFilter,
            watched = custom?.watched ?: watchedMode,
            genres = custom?.genres ?: defaultGenres(source),
            minRating = custom?.minRating ?: defaultMinRating(source),
        )
    }

    fun defaultMaxAge(source: ListSource) = if (source.automatic) maxAgeMonths else 0
    fun defaultGlobalHits(source: ListSource) = source.automatic && globalHitsOnly
    fun defaultGenres(source: ListSource) = if (source.automatic) genreFilter else GenreFilter()
    fun defaultMinRating(source: ListSource) = if (source.automatic) minRating else 0.0

    // --- Look ---

    var outputWidth: Int
        get() = preferences.getInt(look(KEY_WIDTH), 1920)
        set(value) = preferences.edit { putInt(look(KEY_WIDTH), value) }

    /** See [Settings.STYLES]; the settings of the parts a style hides are kept for when they come back. */
    var style: String
        get() = preferences.getString(look(KEY_STYLE), null)?.takeIf { it in Settings.STYLES } ?: Settings.STYLE_FULL
        set(value) = preferences.edit { putString(look(KEY_STYLE), value) }

    /** See [Settings.SHADES]. */
    var shade: String
        get() = preferences.getString(look(KEY_SHADE), null)?.takeIf { it in Settings.SHADES } ?: Settings.SHADE_TINT
        set(value) = preferences.edit { putString(look(KEY_SHADE), value) }

    /** See [Settings.BOTTOM_SHADES]. */
    var bottomShade: Int
        get() = preferences.getInt(look(KEY_BOTTOM_SHADE), Settings.BOTTOM_SHADE_NORMAL).takeIf { it in Settings.BOTTOM_SHADES } ?: Settings.BOTTOM_SHADE_NORMAL
        set(value) = preferences.edit { putInt(look(KEY_BOTTOM_SHADE), value) }

    /** See [Settings.POSITIONS]; Projectivy's rows may cover the bottom ones, depending on the user's layout. */
    var position: String
        get() = preferences.getString(look(KEY_POSITION), null)?.takeIf { it in Settings.POSITIONS || it == Settings.POSITION_ROTATE } ?: Settings.POSITION_TOP_LEFT
        set(value) = preferences.edit { putString(look(KEY_POSITION), value) }

    /** Where [Settings.POSITION_ROTATE] takes the text: at least two of [Settings.POSITIONS]; by default not under the launcher's rows. */
    var rotation: List<String>
        get() = preferences.getString(look(KEY_ROTATION), null)?.split(',').orEmpty().filter { it in Settings.POSITIONS }
            .takeIf { it.size >= 2 } ?: Settings.POSITIONS.filter { isScreensaver || !it.startsWith("bottom") }
        set(value) = preferences.edit { putString(look(KEY_ROTATION), value.joinToString(",")) }

    /** Where the wallpaper number [index] of a set has its text. */
    fun positionAt(index: Int): String = if (position == Settings.POSITION_ROTATE) rotation.let { it[index % it.size] } else position

    /** See [Settings.LOGO_MODES]; earlier versions stored "logo_fallback" and "require_logo" (read until changed). */
    var logoMode: String
        get() = preferences.getString(look(KEY_LOGO_MODE), null) ?: run {
            val fallback = preferences.getBoolean(look(KEY_LOGO_FALLBACK), false)
            val required = preferences.getBoolean(look(KEY_REQUIRE_LOGO), false)
            when {
                fallback && required -> Settings.LOGO_ORIGINAL_OR_SKIP
                required -> Settings.LOGO_SKIP
                fallback -> Settings.LOGO_ORIGINAL
                else -> Settings.LOGO_TEXT
            }
        }
        set(value) = preferences.edit { putString(look(KEY_LOGO_MODE), value); remove(look(KEY_REQUIRE_LOGO)) }

    /** See [Settings.SYNOPSIS_MODES]; "logo_fallback" briefly covered the synopsis too. */
    var synopsisMode: String
        get() = preferences.getString(look(KEY_SYNOPSIS_MODE), null)
            ?: if (preferences.getBoolean(look(KEY_LOGO_FALLBACK), false)) Settings.SYNOPSIS_ENGLISH else Settings.SYNOPSIS_HIDE
        set(value) = preferences.edit { putString(look(KEY_SYNOPSIS_MODE), value) }

    /** See [Settings.PART_LANGUAGES]; [logoMode] applies when there is no logo in this language. */
    var titleLanguage: String
        get() = Settings.partLanguage(look(KEY_TITLE_LANGUAGE))
        set(value) = preferences.edit { putString(look(KEY_TITLE_LANGUAGE), value) }

    /** See [Settings.PART_LANGUAGES]; [synopsisMode] applies when there is no synopsis in this language. */
    var synopsisLanguage: String
        get() = Settings.partLanguage(look(KEY_SYNOPSIS_LANGUAGE))
        set(value) = preferences.edit { putString(look(KEY_SYNOPSIS_LANGUAGE), value) }

    /** English instead of the original language (or an untranslated title) when the original is in another alphabet. */
    var latinOriginals: Boolean
        get() = preferences.getBoolean(look(KEY_LATIN_ORIGINALS), false)
        set(value) = preferences.edit { putBoolean(look(KEY_LATIN_ORIGINALS), value) }

    /** [titleLanguage] and [synopsisLanguage] as TMDB locales. */
    val partLanguages: PartLanguages
        get() = PartLanguages(Settings.resolve(titleLanguage), Settings.resolve(synopsisLanguage))

    /** The style draws the logo (or the title instead). */
    val drawsLogo: Boolean get() = style != Settings.STYLE_BACKDROP

    /** The style draws the header, synopsis, cast and footer (each as set). */
    val drawsDetails: Boolean get() = style == Settings.STYLE_FULL

    // A part the style hides doesn't fetch anything or skip titles
    val logoFallback: Boolean get() = drawsLogo && (logoMode == Settings.LOGO_ORIGINAL || logoMode == Settings.LOGO_ORIGINAL_OR_SKIP)
    val requireLogo: Boolean get() = drawsLogo && (logoMode == Settings.LOGO_ORIGINAL_OR_SKIP || logoMode == Settings.LOGO_SKIP)
    val requireSynopsis: Boolean get() = drawsDetails && synopsisMode == Settings.SYNOPSIS_SKIP

    var showProviders: Boolean
        get() = preferences.getBoolean(look(KEY_PROVIDERS), true)
        set(value) = preferences.edit { putBoolean(look(KEY_PROVIDERS), value) }

    /** Synopsis lines on the wallpaper: fewer lines keep the info block clear of the launcher rows. */
    var overviewLines: Int
        get() = preferences.getInt(look(KEY_OVERVIEW_LINES), 4)
        set(value) = preferences.edit { putInt(look(KEY_OVERVIEW_LINES), value) }

    /** Main cast under the synopsis; off by default, it pushes the footer down. */
    var showCast: Boolean
        get() = preferences.getBoolean(look(KEY_CAST), false)
        set(value) = preferences.edit { putBoolean(look(KEY_CAST), value) }

    /** Faces of the actors shown, see [Settings.CAST_PHOTO_MODES] (together by default); only images, no API requests. */
    var castPhotos: String
        get() = preferences.getString(look(KEY_CAST_PHOTO_MODE), null)?.takeIf { it in Settings.CAST_PHOTO_MODES }
            ?: if (preferences.getBoolean(look(KEY_CAST_PHOTOS), true)) Settings.CAST_PHOTOS_GROUPED else Settings.CAST_PHOTOS_NONE
        set(value) = preferences.edit { putString(look(KEY_CAST_PHOTO_MODE), value).remove(look(KEY_CAST_PHOTOS)) }

    /**
     * With both footer parts on, show the list source only when the title has no streaming service.
     * Default for new installs; existing installs keep showing both (decided once, on first read).
     */
    var sourceFallback: Boolean
        get() {
            if (!preferences.contains(look(KEY_SOURCE_FALLBACK))) sourceFallback = lastGeneration == 0L
            return preferences.getBoolean(look(KEY_SOURCE_FALLBACK), false)
        }
        set(value) = preferences.edit { putBoolean(look(KEY_SOURCE_FALLBACK), value) }

    /** With both footer parts always on, the list source next to the streaming services instead of under them (its name shortened to fit). */
    var sourceInline: Boolean
        get() = preferences.getBoolean(look(KEY_SOURCE_INLINE), false)
        set(value) = preferences.edit { putBoolean(look(KEY_SOURCE_INLINE), value) }

    /** Footer line naming the list each title came from ("Trending · TMDB"). */
    var showSource: Boolean
        get() = preferences.getBoolean(look(KEY_SHOW_SOURCE), true)
        set(value) = preferences.edit { putBoolean(look(KEY_SHOW_SOURCE), value) }

    /** Scores shown on the wallpapers, in order; by default TMDB's, as before they could be chosen. */
    var ratingSources: List<RatingSource>
        get() = ratingSourcesKey.split(',').mapNotNull(RatingSource::of)
        set(value) = preferences.edit { putString(look(KEY_RATINGS), value.joinToString(",") { it.key }) }

    // Read without string(): empty means "no ratings", not the default
    private val ratingSourcesKey: String get() = preferences.getString(look(KEY_RATINGS), null) ?: RatingSource.TMDB.key

    var headerHidden: Set<String>
        get() = preferences.getStringSet(look(KEY_HEADER_HIDDEN), null)?.toSet() ?: emptySet()
        set(value) = preferences.edit { putStringSet(look(KEY_HEADER_HIDDEN), value) }

    fun showsInHeader(item: String) = item !in headerHidden

    /** [Settings.HEADER_BLOCKS] in the user's order (blocks added in later versions go last). */
    var headerOrder: List<String>
        get() {
            val saved = string(look(KEY_HEADER_ORDER)).split(',').filter { it in Settings.HEADER_BLOCKS }.distinct()
            return saved + (Settings.HEADER_BLOCKS - saved.toSet())
        }
        set(value) = preferences.edit { putString(look(KEY_HEADER_ORDER), value.joinToString(",")) }

    /** Ratings that stay when the line is full (the details give way first); more only where there's room. */
    var minRatings: Int
        get() = preferences.getInt(look(KEY_MIN_RATINGS), 2)
        set(value) = preferences.edit { putInt(look(KEY_MIN_RATINGS), value) }

    // --- What a wallpaper opens ---

    /**
     * Streaming platforms the user pays for, in the order a wallpaper tries them (see [ClickTargets]); their
     * pages are looked up when generating, so adding the first one needs a new set.
     */
    var clickPlatforms: List<Platform>
        get() = preferences.getString(click(KEY_CLICK_PLATFORMS), null).orEmpty().split(',').mapNotNull(Platform::of).distinct()
        set(value) = preferences.edit { putString(click(KEY_CLICK_PLATFORMS), value.joinToString(",") { it.key }) }

    /**
     * What a wallpaper opens when its title is on none of [clickPlatforms]: see [Settings.CLICK_FALLBACKS] and
     * [Settings.CLICK_APP_PREFIX] (an app not installed opens the trailer). Applied when served, without a new set.
     */
    var clickFallback: String
        get() = preferences.getString(click(KEY_CLICK_FALLBACK), null)?.takeIf { it in Settings.CLICK_FALLBACKS || Settings.clickApp(it) != null }
            ?: Settings.CLICK_TRAILER
        set(value) = preferences.edit { putString(click(KEY_CLICK_FALLBACK), value) }

    /** This set needs the platforms' pages: with one set, for either place. */
    val opensPlatforms: Boolean
        get() = if (twoSets) clickPlatforms.isNotEmpty() else WALLPAPER.clickPlatforms.isNotEmpty() || SCREENSAVER.clickPlatforms.isNotEmpty()

    // --- Order ---

    private val orderKey get() = if (isScreensaver) KEY_SCREENSAVER_SHUFFLE else KEY_WALLPAPER_MIX

    /** Its own order (mixed, or one list after another); null follows the general one, [Settings.mixLists]. */
    var ownMix: Boolean?
        get() = if (!isGeneral && preferences.contains(orderKey)) preferences.getBoolean(orderKey, true) else null
        set(value) = preferences.edit { if (value == null) remove(orderKey) else putBoolean(orderKey, value) }

    val mixed: Boolean get() = ownMix ?: Settings.mixLists

    // --- Generation state ---

    var lastGeneration: Long
        get() = preferences.getLong(state(KEY_LAST_GENERATION), 0L)
        set(value) = preferences.edit { putLong(state(KEY_LAST_GENERATION), value) }

    /** Settings signature used for the last successful generation. */
    var lastSignature: String
        get() = preferences.getString(state(KEY_LAST_SIGNATURE), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_SIGNATURE), value) }

    var lastError: String
        get() = preferences.getString(state(KEY_LAST_ERROR), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_ERROR), value) }

    /** Something to know about the last generation that went well (e.g. drawn in 1080p for lack of memory). */
    var lastNotice: String
        get() = preferences.getString(state(KEY_LAST_NOTICE), "") ?: ""
        set(value) = preferences.edit { putString(state(KEY_LAST_NOTICE), value) }

    var generating: Boolean
        get() = preferences.getBoolean(state(KEY_GENERATING), false)
        set(value) = preferences.edit { putBoolean(state(KEY_GENERATING), value) }

    /** While generating: wallpapers made, current list and number of lists. */
    var generationProgress: Triple<Int, Int, Int>?
        get() = preferences.getString(state(KEY_PROGRESS), null)?.split('|')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 3 }?.let { Triple(it[0], it[1], it[2]) }
        set(value) = preferences.edit {
            if (value == null) remove(state(KEY_PROGRESS)) else putString(state(KEY_PROGRESS), "${value.first}|${value.second}|${value.third}")
        }

    /** Everything that changes the generated wallpapers (except the refresh interval). */
    val signature: String
        get() = with(Settings) {
            listOf(
                token.hashCode(), language, region, lists.sorted().joinToString(","), titlesPerList,
                outputWidth, globalHitsOnly, showProviders, selectionMode, watchSource?.let { "$it:$watchedMode" }.orEmpty(),
                // Without the genre field lists don't use, so updating doesn't show a pending change
                maxAgeMonths, excludeAdult, listFilters.toSortedMap().toString().replace(", genres=null", "").replace(", minRating=null", ""),
            // Only when changed, so existing installs don't see a pending change after updating
            ).plus(listOfNotNull(overviewLines.takeIf { it != 4 }, "nosource".takeIf { !showSource }, "fallback".takeIf { showSource && showProviders && sourceFallback },
                "inline".takeIf { showSource && showProviders && !sourceFallback && sourceInline },
                // Its pages are looked up when generating
                "platform".takeIf { opensPlatforms },
                "dups".takeIf { !skipDuplicates }, "logo:$logoMode".takeIf { logoMode != LOGO_TEXT }, "synopsis:$synopsisMode".takeIf { synopsisMode != SYNOPSIS_HIDE },
                "titlelang:$titleLanguage".takeIf { titleLanguage != LANGUAGE_AUTO }, "synopsislang:$synopsisLanguage".takeIf { synopsisLanguage != LANGUAGE_AUTO },
                "ratings:$ratingSourcesKey:$mdbListAvailable".takeIf { ratingSourcesKey != RatingSource.TMDB.key },
                "header:${headerHidden.sorted()}".takeIf { headerHidden.isNotEmpty() },
                "ratingage:$maxRatingAge".takeIf { maxRatingAge > 0 }, "minrating:$minRating".takeIf { minRating > 0 },
                "order:${headerOrder.joinToString(",")}".takeIf { headerOrder != HEADER_BLOCKS },
                "minratings:$minRatings".takeIf { minRatings != 2 }, "cast".takeIf { showCast }, "castphotos:$castPhotos".takeIf { showCast && castPhotos != CAST_PHOTOS_GROUPED }, "style:$style".takeIf { style != STYLE_FULL },
                "position:$position${if (position == Settings.POSITION_ROTATE) rotation else ""}".takeIf { position != POSITION_TOP_LEFT && drawsLogo }, "shade:$shade".takeIf { shade != SHADE_TINT },
                "bottomshade:$bottomShade".takeIf { bottomShade != Settings.BOTTOM_SHADE_NORMAL },
                "latin".takeIf { latinOriginals },
                "trailerlang:$trailerLanguage".takeIf { trailerLanguage != LANGUAGE_AUTO },
                "trailerfallback:$trailerFallback".takeIf { trailerFallback != TRAILER_ENGLISH },
                "genres:${genreFilter.only}:${genreFilter.ids.sorted()}".takeIf { !genreFilter.isOff },
                "blocked:${blockedTitles.map { "${it.type.path}:${it.tmdbId}" }.sorted().hashCode()}".takeIf { blockedTitles.isNotEmpty() })).joinToString("|")
        }

    /** True when the current wallpapers were generated with different settings. */
    val hasPendingChanges: Boolean
        get() = source.run { lastGeneration > 0 && lastSignature != signature }

    companion object {
        val GENERAL = Profile("general", "")
        val WALLPAPER = Profile("wallpaper", "wp_")
        val SCREENSAVER = Profile("screensaver", "ss_")

        /** Each place has its own set, because the lists or look of one aren't the general ones. */
        val twoSets: Boolean get() = WALLPAPER.ownSet || SCREENSAVER.ownSet

        fun of(id: String?): Profile = when (id) {
            SCREENSAVER.id -> SCREENSAVER
            GENERAL.id -> GENERAL
            else -> WALLPAPER
        }

        private const val KEY_LISTS = "lists"
        private const val KEY_COUNT = "titles_per_list"
        private const val KEY_GLOBAL_HITS = "global_hits_only"
        private const val KEY_MAX_AGE = "max_age_months"
        private const val KEY_EXCLUDE_ADULT = "exclude_adult"
        private const val KEY_MAX_RATING_AGE = "max_rating_age"
        private const val KEY_MIN_RATING = "min_rating"
        private const val KEY_GENRE_FILTER = "genre_filter"
        private const val KEY_SKIP_DUPLICATES = "skip_duplicates"
        private const val KEY_SELECTION = "selection_mode"
        private const val KEY_WATCHED = "watched_mode"
        private const val KEY_LIST_FILTERS = "list_filters"

        private const val KEY_WIDTH = "output_width"
        private const val KEY_STYLE = "style"
        private const val KEY_POSITION = "block_position"
        private const val KEY_ROTATION = "block_rotation"
        private const val KEY_SHADE = "shade"
        private const val KEY_BOTTOM_SHADE = "bottom_shade"
        private const val KEY_LATIN_ORIGINALS = "latin_originals"
        private const val KEY_LOGO_MODE = "logo_mode"
        private const val KEY_LOGO_FALLBACK = "logo_fallback"
        private const val KEY_REQUIRE_LOGO = "require_logo"
        private const val KEY_SYNOPSIS_MODE = "synopsis_mode"
        private const val KEY_TITLE_LANGUAGE = "title_language"
        private const val KEY_SYNOPSIS_LANGUAGE = "synopsis_language"
        private const val KEY_PROVIDERS = "show_providers"
        private const val KEY_OVERVIEW_LINES = "overview_lines"
        private const val KEY_CAST = "show_cast"
        /** Old on/off switch of the cast photos, read until [castPhotos] is first set. */
        private const val KEY_CAST_PHOTOS = "cast_photos"
        private const val KEY_CAST_PHOTO_MODE = "cast_photo_mode"
        private const val KEY_SOURCE_FALLBACK = "source_fallback"
        private const val KEY_SOURCE_INLINE = "source_inline"
        private const val KEY_SHOW_SOURCE = "show_source"
        private const val KEY_RATINGS = "rating_sources"
        private const val KEY_HEADER_HIDDEN = "header_hidden"
        private const val KEY_HEADER_ORDER = "header_order"
        private const val KEY_MIN_RATINGS = "min_ratings"

        private const val KEY_CLICK_PLATFORMS = "click_platforms"
        private const val KEY_CLICK_FALLBACK = "click_fallback"
        private const val KEY_WALLPAPER_MIX = "wallpaper_mix"
        private const val KEY_SCREENSAVER_SHUFFLE = "screensaver_shuffle"

        private const val KEY_LAST_GENERATION = "_last_generation"
        private const val KEY_LAST_SIGNATURE = "_last_signature"
        private const val KEY_LAST_ERROR = "_last_error"
        private const val KEY_LAST_NOTICE = "_last_notice"
        private const val KEY_GENERATING = "_generating"
        private const val KEY_PROGRESS = "_generation_progress"

        /** Until 1.2 a list made 10 wallpapers unless told otherwise: whoever updates keeps that. */
        fun keepOldCount() = Settings.preferences.edit {
            for (key in listOf(KEY_COUNT, "ss_$KEY_COUNT")) if (!Settings.preferences.contains(key)) putInt(key, 10)
        }

        /** What "own" copies from the general settings when it's turned on. */
        private val CLICK_KEYS = listOf(KEY_CLICK_PLATFORMS, KEY_CLICK_FALLBACK)
        private val LISTS_KEYS = listOf(KEY_LISTS, KEY_COUNT, KEY_GLOBAL_HITS, KEY_MAX_AGE, KEY_EXCLUDE_ADULT, KEY_MAX_RATING_AGE, KEY_MIN_RATING,
            KEY_GENRE_FILTER, KEY_SKIP_DUPLICATES, KEY_SELECTION, KEY_WATCHED, KEY_LIST_FILTERS)
        private val LOOK_KEYS = listOf(KEY_WIDTH, KEY_STYLE, KEY_POSITION, KEY_ROTATION, KEY_LOGO_MODE, KEY_LOGO_FALLBACK, KEY_REQUIRE_LOGO, KEY_SYNOPSIS_MODE,
            KEY_TITLE_LANGUAGE, KEY_SYNOPSIS_LANGUAGE, KEY_PROVIDERS, KEY_OVERVIEW_LINES, KEY_CAST, KEY_CAST_PHOTOS, KEY_CAST_PHOTO_MODE,
            KEY_SOURCE_FALLBACK, KEY_SOURCE_INLINE, KEY_SHOW_SOURCE, KEY_RATINGS, KEY_HEADER_HIDDEN, KEY_HEADER_ORDER, KEY_MIN_RATINGS,
            KEY_SHADE, KEY_BOTTOM_SHADE, KEY_LATIN_ORIGINALS)
    }
}

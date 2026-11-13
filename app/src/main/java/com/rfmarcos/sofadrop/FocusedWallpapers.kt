package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonObject
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A movie, series or episode card focused in Projectivy: all it tells is the card's title, its type
 * (TvContract's PreviewProgramColumns.TYPE_*) and its image.
 */
data class FocusedCard(val title: String, val type: Int, val image: String?) {
    val media: MediaType = if (type == FocusedWallpapers.TYPE_MOVIE) MediaType.MOVIE else MediaType.TV

    /** The TMDB episode still the card shows ("/abc.jpg"), as Nuvio's "Continue watching" does. */
    val still: String? = if (type == FocusedWallpapers.TYPE_TV_EPISODE) FocusedWallpapers.stillOf(image) else null

    /** Names the card in caches and file names: its still, or its kind and title. */
    val key: String = still?.let { "still-${it.trim('/').substringBefore('.')}" }
        ?: "${media.path}-${Integer.toHexString(FocusedWallpapers.normalized(title).hashCode())}"
}

/**
 * Wallpaper of the card focused in Projectivy (see [Settings.focusedCard]), in beta. The title is found
 * on TMDB: an episode by its still when the card shows one (the series searched by the card's title, and
 * the still among its episodes), anything else by its exact name (Apple TV's channel names them in the
 * TV's language). It is then drawn like any wallpaper: with the still and the episode's synopsis, or as
 * its series or movie. What each card gave is kept in [FocusedLog], for users to send.
 *
 * Projectivy doesn't wait for the answer, but applies it when it comes: the request waits for the drawing
 * (a few seconds, in 1080p to be quicker). Drawn wallpapers stay in files/wallpapers/focused/, named after
 * the look-affecting settings, for [MAX_AGE_MILLIS] and up to [MAX_FILES].
 */
object FocusedWallpapers {
    private const val TAG = "FocusedWallpapers"
    const val DIR = "focused"
    /** How often a waiting request checks that its card still has the focus. */
    private const val WAIT_SLICE_MILLIS = 200L
    private const val JPEG_QUALITY = 90
    private const val MAX_WIDTH = 1920
    private const val MAX_FILES = 50
    private const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L
    /** Search results tried for an episode's still: the first is almost always it, the next for series with the same name. */
    private const val SHOWS_TRIED = 3
    /** Results named in the log when none is named like the card. */
    private const val CLOSEST_LOGGED = 3

    /** TvContractCompat.PreviewProgramColumns: the types that are a movie or a series. */
    const val TYPE_MOVIE = 0
    const val TYPE_TV_SERIES = 1
    const val TYPE_TV_SEASON = 2
    const val TYPE_TV_EPISODE = 3

    private val stillPattern = Regex("""^https://image\.tmdb\.org/t/p/[a-z0-9]+(/[A-Za-z0-9]+\.jpg)$""")
    private val marks = Regex("""\p{M}+""")
    private val notAlphanumeric = Regex("""[^\p{L}\p{N}]+""")
    /** " / " between a translated and an original title, as in kino.pub's "Терапия / Shrinking". */
    private val bilingualSeparator = Regex("""\s+/\s+""")

    /** What each card is, by [FocusedCard.key]. */
    private val found = ConcurrentHashMap<String, Match>()
    /** Cards that matched nothing: not searched again until Sofadrop restarts. */
    private val unmatched = ConcurrentHashMap.newKeySet<String>()
    private val drawing = ConcurrentHashMap<String, Future<*>>()
    private val executor = Executors.newSingleThreadExecutor()
    /** The last card asked for: cards queued before it was focused aren't drawn. */
    @Volatile private var wanted: String? = null

    private data class Episode(val season: Int, val number: Int, val name: String, val overview: String)
    /** What the card is, and how it was found (for [FocusedLog]). */
    private data class Match(val ref: TitleRef, val episode: Episode?, val note: String)

    /** The card for a focused program, when it's a movie, series or episode with a title; null otherwise. */
    fun card(title: String?, type: Int?, iconUri: String?): FocusedCard? {
        if (type == null || type !in TYPE_MOVIE..TYPE_TV_EPISODE || title.isNullOrBlank()) return null
        return FocusedCard(title.trim(), type, iconUri)
    }

    /** True when TMDB has nothing named like [card] (until Sofadrop restarts). */
    fun matchesNothing(card: FocusedCard): Boolean = card.key in unmatched

    /** "/abc.jpg" from a TMDB image URL of any size; null for any other image. */
    fun stillOf(iconUri: String?): String? = iconUri?.let { stillPattern.find(it)?.groupValues?.get(1) }

    /**
     * The names [title] may go by on TMDB: the whole title and, for a bilingual one like "Терапия / Shrinking"
     * (kino.pub), each side of the " / ".
     */
    fun titleVariants(title: String): List<String> =
        (listOf(title) + title.split(bilingualSeparator)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** "matchboxlapelicula" for "Matchbox: La película": names compared without case, accents or punctuation. */
    fun normalized(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace(notAlphanumeric, "")

    /**
     * The wallpaper for [card] in [mode] ([Settings.FOCUSED_EPISODE] or [Settings.FOCUSED_SERIES]), waiting up
     * to [waitMillis] for it to be drawn while [stillWanted]. Until then: the episode's still alone (episode mode), or null.
     */
    fun wallpaper(
        context: Context, card: FocusedCard, mode: String, waitMillis: Long = 0, stillWanted: () -> Boolean = { true },
    ): Wallpaper? {
        val prefix = filePrefix()
        cached(context, card, mode, prefix)?.let { return finished(context, it, card) }
        if (card.key in unmatched) return fallback(card, mode)
        val taskKey = "$mode/${card.key}"
        wanted = taskKey
        val task = drawing.computeIfAbsent(taskKey) {
            val appContext = context.applicationContext
            executor.submit {
                try {
                    if (wanted == taskKey) draw(appContext, card, mode, prefix)
                    else Log.d(TAG, "${card.title}: no longer focused, not drawn")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not draw ${card.title}", e)
                    FocusedLog.add(appContext, card, "failed · ${GenerationLog.describe(e)}")
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Out of memory drawing ${card.title}", e)
                    FocusedLog.add(appContext, card, "failed · out of memory")
                } finally {
                    drawing.remove(taskKey)
                }
            }
        }
        if (waitMillis > 0) {
            // In short slices, so a request for a card the focus has left frees Projectivy's thread at once
            val deadline = System.currentTimeMillis() + waitMillis
            while (stillWanted() && !task.isDone) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    task.get(minOf(left, WAIT_SLICE_MILLIS), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                } catch (_: ExecutionException) {
                }
            }
            cached(context, card, mode, prefix)?.let { return finished(context, it, card) }
        }
        return fallback(card, mode)
    }

    /** [card]'s drawn wallpaper, once it's known what the card is and drawn with the current settings. */
    private fun cached(context: Context, card: FocusedCard, mode: String, prefix: String): File? {
        val match = found[card.key] ?: return null
        val file = fileOf(context, prefix, match, mode).takeIf { it.exists() } ?: return null
        // The ones in use stay longest
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    private fun finished(context: Context, file: File, card: FocusedCard): Wallpaper {
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.wallpapers")
            .appendPath(DIR).appendPath(file.name).build()
        return wallpaper(uri.toString(), card.title)
    }

    private fun fallback(card: FocusedCard, mode: String) =
        if (mode == Settings.FOCUSED_EPISODE && card.still != null) wallpaper(TmdbClient.IMAGE_URL + "original" + card.still, card.title) else null

    private fun wallpaper(uri: String, title: String) =
        Wallpaper(uri = uri, type = WallpaperType.IMAGE, displayMode = WallpaperDisplayMode.CROP, title = title)

    private fun dir(context: Context) = File(File(context.filesDir, WallpaperStore.DIR), DIR)

    /** Drawn with other settings, it's another file. */
    private fun filePrefix() = Integer.toHexString(Profile.WALLPAPER.signature.hashCode())

    /** "1a2b3c-tv-1399.jpg" for a series or a movie, "1a2b3c-tv-1399-s1e3.jpg" for an episode. */
    private fun fileOf(context: Context, prefix: String, match: Match, mode: String): File {
        val episode = match.episode?.takeIf { mode == Settings.FOCUSED_EPISODE }?.let { "-s${it.season}e${it.number}" }.orEmpty()
        return File(dir(context), "$prefix-${match.ref.type.path}-${match.ref.tmdbId}$episode.jpg")
    }

    private fun draw(context: Context, card: FocusedCard, mode: String, prefix: String) {
        val start = System.currentTimeMillis()
        Settings.init(context)
        val profile = Profile.WALLPAPER
        val factory = WallpaperFactory(context, minOf(profile.outputWidth, MAX_WIDTH), profile)
        val match = found[card.key] ?: find(context, factory.client, card)?.also { found[card.key] = it }
        if (match == null) {
            unmatched += card.key
            return
        }
        val file = fileOf(context, prefix, match, mode)
        if (file.exists()) {
            FocusedLog.add(context, card, "${match.note} · cached")
            return
        }
        val info = factory.build(match.ref) ?: run {
            FocusedLog.add(context, card, "${match.note} · not drawn")
            return
        }
        val details = profile.drawsDetails
        val language = Settings.language
        val episode = match.episode?.takeIf { mode == Settings.FOCUSED_EPISODE }
        // No list behind it: no list name in the footer
        val drawn = info.copy(sourceLabel = null, sourceProvider = null).let { title ->
            if (episode == null || card.still == null) title
            else title.copy(
                backdropPath = card.still,
                extraInfo = if (details && profile.showsInHeader(Settings.HEADER_RUNTIME))
                    listOf(Languages.episodeCode(language, episode.season, episode.number), episode.name).filter { it.isNotBlank() }.joinToString(" · ")
                else title.extraInfo,
                // Untranslated: the series' synopsis, which already follows the synopsis setting
                overview = if (details) episode.overview.ifBlank { title.overview } else title.overview,
            )
        }
        val bitmap = factory.render(drawn)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        tmp.renameTo(file)
        prune(context)
        val seconds = (System.currentTimeMillis() - start) / 100 / 10.0
        Log.i(TAG, "${card.title}: ${file.name} drawn in $seconds s")
        FocusedLog.add(context, card, "${match.note} · ${if (episode != null) "episode" else match.ref.type.path} in $seconds s")
    }

    /** Older than [MAX_AGE_MILLIS] or beyond the [MAX_FILES] last used: they go. */
    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
            if (index >= MAX_FILES || now - file.lastModified() > MAX_AGE_MILLIS) file.delete()
        }
    }

    /** What [card] is; logged once drawn, or here when it's nothing TMDB has (with what it found instead). */
    private fun find(context: Context, client: TmdbClient, card: FocusedCard): Match? {
        card.still?.let { findEpisode(client, card)?.let { return it } }
        val stillMissed = if (card.still != null) "still matched no episode · " else ""
        val (match, closest) = findTitle(client, card)
        if (match == null) FocusedLog.add(context, card, "${stillMissed}nothing named so on TMDB · closest: ${closest.ifEmpty { "none" }}")
        return match?.let { it.copy(note = stillMissed + it.note) }
    }

    /** The episode whose still is [card]'s image, among the first series named like it; the latest seasons first. */
    private fun findEpisode(client: TmdbClient, card: FocusedCard): Match? {
        val language = Settings.language
        val shows = client.get("search/tv?query=${URLEncoder.encode(card.title, "UTF-8")}&language=$language")
            ?.objects("results").orEmpty().take(SHOWS_TRIED)
        for (show in shows) {
            val id = show.int("id") ?: continue
            val seasons = client.get("tv/$id?language=$language")?.objects("seasons").orEmpty()
                .mapNotNull { it.int("season_number") }.sortedByDescending { if (it == 0) -1 else it }
            for (season in seasons) {
                val episode = client.get("tv/$id/season/$season?language=$language")?.objects("episodes").orEmpty()
                    .firstOrNull { it.str("still_path") == card.still } ?: continue
                val number = episode.int("episode_number") ?: 0
                val found = Episode(season, number, episode.str("name").orEmpty(), episode.str("overview").orEmpty())
                return Match(TitleRef(MediaType.TV, id), found, "tv/$id ${show.str("name").orEmpty()} S${season}E$number by still")
            }
        }
        return null
    }

    /**
     * How good a search result is for a card: each of its names (translated, original) that matches the card
     * counts double, and having a backdrop to draw counts one. 0: not named like the card at all.
     */
    fun candidateScore(namesMatched: Int, hasBackdrop: Boolean): Int =
        if (namesMatched <= 0) 0 else namesMatched * 2 + (if (hasBackdrop) 1 else 0)

    /** The score that needs no further searching: every name of the card matched, with a backdrop. */
    fun perfectScore(bilingual: Boolean): Int = candidateScore(if (bilingual) 2 else 1, true)

    private class Candidate(val media: MediaType, val id: Int, val result: JsonObject, val query: String, val language: String, val score: Int)

    /**
     * The movie or series named exactly like [card] (or originally named so), in the TV's language and then the
     * wallpapers'; between equals, the first TMDB gives (the most relevant). Its note says how sure it was.
     * A bilingual title ("Терапия / Shrinking", as kino.pub names them) is also searched by each side; a result
     * named like both sides, and one with a backdrop, goes first ([candidateScore]). The card's kind is tried
     * first, then the other one: some apps (kino.pub) mark movies as series. Without a match, the first
     * results instead ("Lucky Hank (2023), …"), for the log.
     */
    private fun findTitle(client: TmdbClient, card: FocusedCard): Pair<Match?, String> {
        val variants = titleVariants(card.title)
        val targets = variants.map { normalized(it) }.toSet()
        val enough = perfectScore(variants.size > 1)
        val medias = listOf(card.media) + MediaType.entries.filter { it != card.media }
        val languages = listOf(Locale.getDefault().toLanguageTag(), Settings.language).distinct()
        var closest = ""
        var best: Candidate? = null
        search@ for (media in medias) {
            val name = if (media == MediaType.MOVIE) "title" else "name"
            val original = "original_$name"
            for (query in variants) {
                for (language in languages) {
                    val results = client.get("search/${media.path}?query=${URLEncoder.encode(query, "UTF-8")}&language=$language")
                        ?.objects("results").orEmpty()
                    if (closest.isEmpty()) closest = results.take(CLOSEST_LOGGED).joinToString(", ") { "${it.str(name).orEmpty()} (${yearOf(it)})" }
                    for (result in results) {
                        val id = result.int("id") ?: continue
                        val matched = listOf(name, original).count { normalized(result.str(it).orEmpty()) in targets }
                        val score = candidateScore(matched, !result.str("backdrop_path").isNullOrEmpty())
                        // Strictly better only: between equals, TMDB's order (relevance) and the card's own kind win
                        if (score > (best?.score ?: 0)) best = Candidate(media, id, result, query, language, score)
                    }
                    if ((best?.score ?: 0) >= enough) break@search
                }
            }
        }
        val found = best ?: return null to closest
        val name = if (found.media == MediaType.MOVIE) "title" else "name"
        val by = if (found.query == card.title) "by name" else "by name \"${found.query}\""
        val kind = if (found.media != card.media) " (card says ${card.media.path})" else ""
        val note = "${found.media.path}/${found.id} ${found.result.str(name).orEmpty()} (${yearOf(found.result)}) $by ${found.language}$kind · score ${found.score}/$enough"
        return Match(TitleRef(found.media, found.id), null, note) to closest
    }

    private fun yearOf(result: JsonObject) = (result.str("release_date") ?: result.str("first_air_date")).orEmpty().take(4).ifEmpty { "?" }
}

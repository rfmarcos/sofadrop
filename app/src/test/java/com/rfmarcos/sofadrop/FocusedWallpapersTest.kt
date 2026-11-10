package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Focused cards, as Projectivy describes them (Nuvio's "Continue watching" and the Apple TV channel, 2026-11). */
class FocusedWallpapersTest {

    @Test
    fun tmdbStillOfAnySize() {
        assertEquals("/jkCAUGWBTXC1WCN8qtEV1XXGAIb.jpg", FocusedWallpapers.stillOf("https://image.tmdb.org/t/p/w500/jkCAUGWBTXC1WCN8qtEV1XXGAIb.jpg"))
        assertEquals("/jkCAUGWBTXC1WCN8qtEV1XXGAIb.jpg", FocusedWallpapers.stillOf("https://image.tmdb.org/t/p/original/jkCAUGWBTXC1WCN8qtEV1XXGAIb.jpg"))
    }

    @Test
    fun otherImagesAreNotStills() {
        assertNull(FocusedWallpapers.stillOf(null))
        assertNull(FocusedWallpapers.stillOf("https://is1-ssl.mzstatic.com/image/thumb/jW5kHqagE5z8D3awVcgFiA/1920x1080.jpg"))
        assertNull(FocusedWallpapers.stillOf("content://com.example/image.jpg"))
        assertNull(FocusedWallpapers.stillOf("https://image.tmdb.org.evil.com/t/p/w500/abc.jpg"))
    }

    @Test
    fun nuvioEpisodeIsKnownByItsStill() {
        val card = FocusedWallpapers.card("Solo Leveling", 3, "https://image.tmdb.org/t/p/w500/k2ZJYbwUzBW7HNNMMmWBv3r8RhT.jpg")!!
        assertEquals("/k2ZJYbwUzBW7HNNMMmWBv3r8RhT.jpg", card.still)
        assertEquals(MediaType.TV, card.media)
        assertEquals("still-k2ZJYbwUzBW7HNNMMmWBv3r8RhT", card.key)
    }

    @Test
    fun appleTvMoviesAndSeriesByName() {
        val movie = FocusedWallpapers.card("Matchbox: la película", 0, "https://is1-ssl.mzstatic.com/image/thumb/3xa3ptAVy2hOk5tnoAzXNA/1920x1080.jpg")!!
        assertEquals(MediaType.MOVIE, movie.media)
        assertNull(movie.still)
        val series = FocusedWallpapers.card("Slow Horses", 1, "https://is1-ssl.mzstatic.com/image/thumb/x5JjmiSD75wN12-HmmZRcg/1920x1080.jpg")!!
        assertEquals(MediaType.TV, series.media)
        // A movie and a series with the same name are different cards
        assertNotEquals(FocusedWallpapers.card("Brothers", 0, null)!!.key, FocusedWallpapers.card("Brothers", 1, null)!!.key)
        // A TMDB image on a card that isn't an episode isn't a still
        assertNull(FocusedWallpapers.card("Tenzing", 0, "https://image.tmdb.org/t/p/w500/abc.jpg")!!.still)
    }

    @Test
    fun onlyMoviesSeriesAndEpisodes() {
        // A clip (YouTube), an event, a Now Playing card (media types from 100), no title
        assertNull(FocusedWallpapers.card("Some video", 4, null))
        assertNull(FocusedWallpapers.card("Match", 5, null))
        assertNull(FocusedWallpapers.card("Solo Leveling", 102, null))
        assertNull(FocusedWallpapers.card("Solo Leveling", null, null))
        assertNull(FocusedWallpapers.card(null, 3, null))
        assertNull(FocusedWallpapers.card(" ", 1, null))
    }

    @Test
    fun namesComparedWithoutCaseAccentsOrPunctuation() {
        assertEquals(FocusedWallpapers.normalized("Matchbox: La película"), FocusedWallpapers.normalized("Matchbox: la pelicula"))
        assertEquals("slowhorses", FocusedWallpapers.normalized("Slow Horses"))
        assertEquals("第2季", FocusedWallpapers.normalized("第2季"))
        assertNotEquals(FocusedWallpapers.normalized("Brothers"), FocusedWallpapers.normalized("Brother"))
    }

    @Test
    fun episodeCodesInTheWallpaperLanguage() {
        assertEquals("T2E3", Languages.episodeCode("es-ES", 2, 3))
        assertEquals("S2E3", Languages.episodeCode("en-US", 2, 3))
        assertEquals("S2E3", Languages.episodeCode("de-DE", 2, 3))
        assertEquals("第2季 第3集", Languages.episodeCode("zh-TW", 2, 3))
    }
}

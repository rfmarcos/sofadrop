package com.rfmarcos.sofadrop

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last cards focused in Projectivy and what [FocusedWallpapers] made of each, while it's in beta: what
 * apps send (title, type, where the image is) varies, and users can show it as text in Settings to share it. Only the image's host is kept, never its address.
 */
object FocusedLog {
    private const val FILE = "focused-cards.txt"
    private const val MAX_LINES = 60
    private val TIME = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** "type 1 · "Nocturno" · is1-ssl.mzstatic.com": what Projectivy said about the card. */
    fun describe(title: String?, type: Int?, image: String?): String {
        val host = image?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it.substringBefore(':') }
        return listOfNotNull("type $type", "\"${title.orEmpty()}\"", host ?: "no image").joinToString(" · ")
    }

    fun add(context: Context, card: FocusedCard, result: String) = add(context, "${describe(card.title, card.type, card.image)} → $result")

    /** A card that isn't a movie, series or episode (a clip, a channel…): left alone. */
    fun ignored(context: Context, title: String?, type: Int?, image: String?) = add(context, "${describe(title, type, image)} → ignored")

    @Synchronized
    private fun add(context: Context, line: String) {
        val lines = read(context)
        // The same card again (focused back and forth) adds nothing
        if (lines.lastOrNull()?.substringAfter("  ") == line) return
        val kept = (lines + "${TIME.format(Date())}  $line").takeLast(MAX_LINES)
        runCatching { file(context).writeText(kept.joinToString("\n")) }
    }

    @Synchronized
    fun read(context: Context): List<String> =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() }.orEmpty().filter { it.isNotBlank() }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** Sofadrop, device, Projectivy, option and languages: the first lines of what's sent. */
    private fun header(context: Context): List<String> {
        val projectivy = runCatching { context.packageManager.getPackageInfo(Projectivy.PACKAGE, 0).versionName }.getOrNull() ?: "?"
        return listOf(
            "Sofadrop ${BuildConfig.VERSION_NAME} focused cards · Projectivy $projectivy · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            "Mode ${Settings.focusedCard} · TV ${Locale.getDefault().toLanguageTag()} · wallpapers ${Settings.language} · ${DeviceMemory.describe(context)}",
            "time  type (0 movie, 1 series, 3 episode) · name · image host → found",
        )
    }

    /**
     * The whole log as plain text, newest cards first, for users to share: plain separators, which are easier
     * to type and which a phone reads more reliably from a photo than "·" and "→".
     */
    fun text(context: Context): String =
        (header(context) + "" + read(context).asReversed()).joinToString("\n").replace(" · ", " | ").replace(" → ", " > ")
}

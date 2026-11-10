package com.rfmarcos.sofadrop

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Updates from the project's GitHub releases (there is no store): [check] looks for a newer release
 * now and then, [UpdateInstaller.install] downloads its APK and hands it to the system installer, which asks the
 * user (GitHub builds only: Google Play updates its own).
 */
object Updater {
    private const val TAG = "SofadropUpdater"
    private const val CHECK_EVERY = 12 * 60 * 60 * 1000L

    internal val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** "owner/repo" from the source code link; null in builds without one (no updates). */
    fun repo(): String? =
        Regex("github\\.com/([^/]+/[^/#?]+)").find(BuildConfig.SOURCE_URL)?.groupValues?.get(1)?.removeSuffix(".git")

    /** True when this build looks for updates: it has a repository, and it's neither the Dev build nor Google Play's. */
    val canCheck: Boolean get() = repo() != null && !BuildConfig.DEBUG && !BuildConfig.PLAY

    /** True when the last check found a release newer than this build (not one found by a GitHub APK replaced from Play). */
    val available: Boolean get() = canCheck && isNewer(Settings.updateVersion, BuildConfig.VERSION_NAME) && Settings.updateApkUrl.isNotBlank()

    /** "1.0.10" > "1.0.9"; a leading "v" and any "-suffix" are ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(version: String) = version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Look for a newer release, at most every 12 hours unless [force]; network errors are only logged. */
    fun check(force: Boolean = false) {
        // The Dev build must not offer to "update" itself to the release
        val repo = repo()?.takeIf { canCheck } ?: return
        if (!force && System.currentTimeMillis() - Settings.updateCheckedAt < CHECK_EVERY) return
        try {
            val request = Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            val release = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
            val apk = release.objects("assets").firstOrNull { it.str("name")?.endsWith(".apk") == true }
            Settings.updateVersion = release.str("tag_name").orEmpty().removePrefix("v")
            Settings.updateApkUrl = apk?.str("browser_download_url").orEmpty()
            Settings.updateNotes = release.str("body").orEmpty()
            Settings.updateCheckedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
        }
    }

    /** A line of the release notes: a section [heading] ("New"), or an item with its bold lead ("Cast") as [title], if any. */
    data class Note(val title: String, val text: String = "", val heading: Boolean = false)

    private val SKIPPED = Regex("(?i)sha-?256\\b.*|.*\\bdownloader code\\b.*|\\**full changelog\\b.*")
    private val BULLET = Regex("^[-*+]\\s+")
    private val LEAD = Regex("^(\\*\\*[^:]+):\\s+(.*)")
    private val LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val CODE = Regex("`+([^`]*)`+")
    private val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
    private val ITALIC = Regex("\\*(\\S(?:.*?\\S)?)\\*|(?<!\\w)_(\\S(?:.*?\\S)?)_(?!\\w)")

    /**
     * The release notes' markdown as plain text for the update screen: headings and items without
     * formatting, links or code marks. The install line (Downloader code) and the APK's checksum are left out.
     */
    fun releaseNotes(markdown: String): List<Note> = markdown.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() || SKIPPED.matches(line) -> null
            line.startsWith("#") -> Note(plain(line.trimStart('#')), heading = true)
            else -> {
                val item = line.replaceFirst(BULLET, "")
                val lead = LEAD.find(item)
                // "**Cast**: optionally, …" reads as a title and a sentence of its own
                if (lead != null) Note(plain(lead.groupValues[1]), plain(lead.groupValues[2]).replaceFirstChar { it.uppercase() })
                else Note("", plain(item))
            }
        }
    }

    private fun plain(text: String) = text
        .replace(LINK, "$1")
        .replace(CODE, "$1")
        .replace(BOLD, "$2")
        .replace(ITALIC) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .trim()
}

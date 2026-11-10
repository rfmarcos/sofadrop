package com.rfmarcos.sofadrop

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * A copy of the wallpapers' set in the shared Pictures/Sofadrop folder, for launchers that rotate the images
 * of a folder (Monet Launcher…) instead of asking a plugin as Projectivy does. Each set replaces the last.
 */
object WallpaperFolder {
    private const val TAG = "WallpaperFolder"
    private const val NAME = "Sofadrop"
    private const val MIME = "image/jpeg"

    /** "Pictures/Sofadrop", as the user finds it in a launcher's folder picker. */
    val path = "${Environment.DIRECTORY_PICTURES}/$NAME"

    /** Something shows the wallpapers' set on the home screen: Projectivy, or a launcher reading the folder. */
    fun shown(context: Context): Boolean = Settings.wallpaperFolder || Projectivy.isInstalled(context)

    /** Android 9 and older need the storage permission to write in Pictures. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasPermission(context: Context): Boolean = !needsPermission ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The current wallpapers' set into the folder, when the folder is on: after each generation and when it's turned on. */
    fun update(context: Context) {
        if (!Settings.wallpaperFolder) return
        val store = WallpaperStore(context, Profile.WALLPAPER)
        val files = store.current().mapNotNull { store.fileOf(it) }.filter { it.exists() }
        if (files.isEmpty()) return
        try {
            replace(context, files)
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy the wallpapers to $path", e)
        }
    }

    /** Empty the folder, once it's turned off. */
    fun clear(context: Context) {
        try {
            replace(context, emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "Could not empty $path", e)
        }
    }

    /**
     * Make [files] the folder's images: same names are written over (so no "name (1).jpg" copies), new ones
     * added, the rest deleted. Android 10 and up only show an app its own images, so others there are left alone.
     */
    @Synchronized
    private fun replace(context: Context, files: List<File>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) replaceInMediaStore(context, files) else replaceInFolder(context, files)
    }

    private fun replaceInMediaStore(context: Context, files: List<File>) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val existing = mutableMapOf<String, Long>()
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$path/"), null)?.use { cursor ->
            while (cursor.moveToNext()) existing[cursor.getString(1)] = cursor.getLong(0)
        }
        for (file in files) {
            val id = existing.remove(file.name)
            if (id != null) {
                resolver.openOutputStream(ContentUris.withAppendedId(collection, id), "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                continue
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("No $path folder")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Can't write $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        existing.values.forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
    }

    private fun replaceInFolder(context: Context, files: List<File>) {
        if (!hasPermission(context)) return
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), NAME)
        if (files.isNotEmpty()) folder.mkdirs()
        val names = files.map { it.name }.toSet()
        val gone = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") && it.name !in names }.orEmpty()
        gone.forEach { it.delete() }
        val written = files.map { file -> file.copyTo(File(folder, file.name), overwrite = true) }
        // So the gallery and launchers' pickers see the change
        val paths = (written + gone).map { it.path }.toTypedArray()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths, null, null)
    }
}

package com.shanpalia.pdm

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

object PdmStorage {
    private val categories = listOf(
        "Images", "Videos", "Music", "APK", "ZIP", "Documents", "Torrents", "Other"
    )

    fun hasPublicAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun root(context: Context): File {
        val base = if (hasPublicAccess()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Downloads")
        }
        return File(base, "PDM")
    }

    fun ensureFolders(context: Context): File {
        val root = root(context)
        root.mkdirs()
        categories.forEach { File(root, it).mkdirs() }
        return root
    }

    fun categoryFor(name: String): String {
        val lower = name.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.endsWith(".heic") -> "Images"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".3gp") -> "Videos"
            lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
                lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".aac") -> "Music"
            lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") -> "APK"
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".bz2") -> "ZIP"
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".xls") ||
                lower.endsWith(".xlsx") || lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "Documents"
            lower.endsWith(".torrent") -> "Torrents"
            else -> "Other"
        }
    }

    fun uniqueFile(directory: File, requestedName: String): File {
        directory.mkdirs()
        val safe = requestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download.bin" }
        var result = File(directory, safe)
        if (!result.exists()) return result
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (result.exists()) {
            result = File(directory, "$base ($index)$ext")
            index++
        }
        return result
    }

    fun uniqueDirectory(parent: File, requestedName: String): File {
        parent.mkdirs()
        val safe = requestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "torrent" }
        var result = File(parent, safe)
        var index = 1
        while (result.exists()) {
            result = File(parent, "$safe ($index)")
            index++
        }
        return result
    }

    fun fileNameFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/').trim()
        return if (name.isBlank()) "download.bin" else name
    }
}

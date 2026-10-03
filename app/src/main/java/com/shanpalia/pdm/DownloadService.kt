package com.shanpalia.pdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private val cancelled = AtomicBoolean(false)
    private val channelId = "pdm_downloads"

    override fun onCreate() {
        super.onCreate(); createChannel(); startForeground(1001, notification("PDM is ready"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val name = intent.getStringExtra("name") ?: DownloadEngine.fileNameFromUrl(url)
        cancelled.set(false); pool.execute { DownloadEngine.download(this, url, name, cancelled) }
        return START_STICKY
    }
    fun update(text: String, percent: Int) { getSystemService(NotificationManager::class.java).notify(1001, notification("$text • $percent%")) }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, channelId).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("PDM Download Manager").setContentText(text).setOngoing(true).build()
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { cancelled.set(true); pool.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

object DownloadEngine {
    private const val PARTS = 4
    fun fileNameFromUrl(url: String): String = url.substringBefore('?').substringBefore('#').substringAfterLast('/').ifBlank { "download.bin" }

    fun download(service: DownloadService, url: String, name: String, cancelled: AtomicBoolean) {
        try {
            val dir = File(service.getExternalFilesDir("downloads"), "PDM").apply { mkdirs() }
            val target = File(dir, safeName(name))
            val size = contentLength(url)
            if (size <= 0L || !supportsRanges(url)) single(service, url, target, cancelled)
            else segmented(service, url, target, size, cancelled)
            if (!cancelled.get()) service.update("Completed: ${target.name}", 100)
        } catch (e: Exception) { if (!cancelled.get()) service.update("Failed: ${e.message ?: "network error"}", 0) }
    }

    private fun contentLength(url: String): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 10000; c.readTimeout = 10000
        return try { c.connect(); c.contentLengthLong } finally { c.disconnect() }
    }

    private fun supportsRanges(url: String): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=0-0"); c.connectTimeout = 10000; c.readTimeout = 10000
        return try { c.connect(); c.responseCode == HttpURLConnection.HTTP_PARTIAL } finally { c.disconnect() }
    }

    private fun segmented(service: DownloadService, url: String, target: File, total: Long, cancelled: AtomicBoolean) {
        val parts = minOf(PARTS, ((total + 1_048_575) / 1_048_576).toInt().coerceAtLeast(1))
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        val executor = Executors.newFixedThreadPool(parts); val done = LongArray(parts)
        val futures = (0 until parts).map { index ->
            val start = total * index / parts; val end = total * (index + 1) / parts - 1
            executor.submit {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("Range", "bytes=$start-$end"); conn.connectTimeout = 15000; conn.readTimeout = 30000
                try {
                    conn.connect(); if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IllegalStateException("Server does not support byte ranges")
                    RandomAccessFile(target, "rw").use { file ->
                        file.seek(start); conn.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024); var n: Int
                            while (input.read(buffer).also { n = it } > 0) {
                                if (cancelled.get()) return@submit
                                file.write(buffer, 0, n); done[index] += n
                                service.update(target.name, ((done.sum() * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                } finally { conn.disconnect() }
            }
        }
        try { futures.forEach { it.get() } } finally { executor.shutdownNow() }
    }

    private fun single(service: DownloadService, url: String, target: File, cancelled: AtomicBoolean) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        try {
            conn.connect(); if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong; var done = 0L
            target.outputStream().use { out -> conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024); var n: Int
                while (input.read(buffer).also { n = it } > 0) {
                    if (cancelled.get()) return
                    out.write(buffer, 0, n); done += n
                    service.update(target.name, if (total > 0) ((done * 100) / total).toInt() else 0)
                }
            }}
        } finally { conn.disconnect() }
    }
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)
}

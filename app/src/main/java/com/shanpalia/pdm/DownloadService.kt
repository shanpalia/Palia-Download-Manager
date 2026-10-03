package com.shanpalia.pdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {
    companion object {
        const val ACTION_HTTP = "com.shanpalia.pdm.HTTP"
        const val ACTION_TORRENT_FILE = "com.shanpalia.pdm.TORRENT_FILE"
        const val ACTION_TORRENT_MAGNET = "com.shanpalia.pdm.TORRENT_MAGNET"
        const val ACTION_TORRENT_URL = "com.shanpalia.pdm.TORRENT_URL"
        const val EXTRA_URL = "url"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "pdm_downloads"
        private const val NOTIFICATION_ID = 1001
    }

    private val executor = Executors.newCachedThreadPool()
    private val activeJobs = AtomicInteger(0)
    private lateinit var torrentSession: SessionManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground(notification("PDM is ready", 0, false))
        torrentSession = SessionManager(false)
        torrentSession.start()
        PdmStorage.ensureFolders(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_HTTP
        activeJobs.incrementAndGet()
        executor.execute {
            try {
                when (action) {
                    ACTION_TORRENT_MAGNET -> downloadMagnet(intent?.getStringExtra(EXTRA_URL).orEmpty())
                    ACTION_TORRENT_FILE -> downloadTorrentFile(File(intent?.getStringExtra(EXTRA_PATH).orEmpty()))
                    ACTION_TORRENT_URL -> downloadTorrentUrl(intent?.getStringExtra(EXTRA_URL).orEmpty())
                    else -> downloadHttp(
                        intent?.getStringExtra(EXTRA_URL).orEmpty(),
                        intent?.getStringExtra(EXTRA_NAME)
                    )
                }
            } catch (t: Throwable) {
                notify("Failed: ${t.message ?: "download error"}", 0, false)
            } finally {
                finishJob()
            }
        }
        return START_NOT_STICKY
    }

    private fun finishJob() {
        if (activeJobs.decrementAndGet() <= 0) {
            activeJobs.set(0)
            stopSelf()
        }
    }

    private fun downloadHttp(urlString: String, requestedName: String?) {
        val url = urlString.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("Only HTTP/HTTPS links are supported")
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "close")
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val guessed = requestedName?.takeIf { it.isNotBlank() }
                ?: URLUtil.guessFileName(url, connection.getHeaderField("Content-Disposition"), connection.contentType)
            val fileName = guessed.ifBlank { PdmStorage.fileNameFromUrl(url) }
            val category = PdmStorage.categoryFor(fileName)
            val directory = File(PdmStorage.ensureFolders(this), category)
            val target = PdmStorage.uniqueFile(directory, fileName)
            val partial = File(target.parentFile, ".${target.name}.part")
            val existing = if (partial.exists()) partial.length() else 0L

            connection.disconnect()

            val resumeConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }

            try {
                resumeConnection.connect()
                val response = resumeConnection.responseCode
                val append = existing > 0L && response == HttpURLConnection.HTTP_PARTIAL
                val startBytes = if (append) existing else 0L
                if (!append && existing > 0L) partial.delete()

                if (response !in 200..299) throw IllegalStateException("HTTP $response")

                val contentLength = resumeConnection.contentLengthLong
                val total = if (contentLength > 0L) startBytes + contentLength else -1L
                var done = startBytes
                var lastNotify = 0L

                partial.outputStream().use { out ->
                    if (append) {
                        out.close()
                        partial.outputStream().use { appendOut ->
                            java.io.RandomAccessFile(partial, "rw").use { raf ->
                                raf.seek(existing)
                                resumeConnection.inputStream.use { input ->
                                    copyStream(input, raf, total) { bytes ->
                                        done = bytes
                                        if (System.currentTimeMillis() - lastNotify > 700) {
                                            lastNotify = System.currentTimeMillis()
                                            val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                                            notify(fileName, percent, true)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        resumeConnection.inputStream.use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var n: Int
                            while (input.read(buffer).also { n = it } > 0) {
                                out.write(buffer, 0, n)
                                done += n
                                if (System.currentTimeMillis() - lastNotify > 700) {
                                    lastNotify = System.currentTimeMillis()
                                    val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                                    notify(fileName, percent, true)
                                }
                            }
                        }
                    }
                }

                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                notify("Completed: ${target.name}", 100, false)
            } finally {
                resumeConnection.disconnect()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyStream(
        input: java.io.InputStream,
        raf: java.io.RandomAccessFile,
        total: Long,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(128 * 1024)
        var done = raf.length()
        var n: Int
        while (input.read(buffer).also { n = it } > 0) {
            raf.write(buffer, 0, n)
            done += n
            onProgress(done)
        }
    }

    private fun downloadTorrentUrl(url: String) {
        val temp = File(cacheDir, "torrent-${System.currentTimeMillis()}.torrent")
        downloadToFile(url, temp) { percent -> notify("Getting torrent metadata", percent, true) }
        try {
            downloadTorrentFile(temp)
        } finally {
            temp.delete()
        }
    }

    private fun downloadToFile(urlString: String, target: File, progress: (Int) -> Unit) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var done = 0L
            target.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buffer).also { n = it } > 0) {
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress(((done * 100) / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTorrentFile(file: File) {
        if (!file.exists()) throw IllegalArgumentException("Torrent file not found")
        val info = TorrentInfo(file)
        if (!info.isValid()) throw IllegalArgumentException("Invalid torrent file")
        startTorrent(info)
    }

    private fun downloadMagnet(magnet: String) {
        if (!magnet.trim().startsWith("magnet:?", ignoreCase = true)) {
            throw IllegalArgumentException("Invalid magnet link")
        }
        val tempDir = File(cacheDir, "magnet-metadata").apply { mkdirs() }
        val data = torrentSession.fetchMagnet(magnet.trim(), 60, tempDir)
            ?: throw IllegalStateException("Could not retrieve torrent metadata")
        val temp = File(tempDir, "metadata-${System.currentTimeMillis()}.torrent")
        temp.writeBytes(data)
        try {
            val info = TorrentInfo(temp)
            if (!info.isValid()) throw IllegalStateException("Invalid magnet metadata")
            startTorrent(info)
        } finally {
            temp.delete()
        }
    }

    private fun startTorrent(info: TorrentInfo) {
        val torrentRoot = File(PdmStorage.ensureFolders(this), "Torrents")
        val safeName = info.name().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank {
            info.infoHash().toString()
        }
        val saveDir = File(PdmStorage.uniqueDirectory(torrentRoot, safeName)).apply { mkdirs() }

        torrentSession.download(info, saveDir)
        val handle = waitForHandle(info.infoHash())
            ?: throw IllegalStateException("Torrent could not be started")

        monitorTorrent(handle, info.name().ifBlank { safeName })
    }

    private fun waitForHandle(hash: Sha1Hash): TorrentHandle? {
        repeat(30) {
            val handle = torrentSession.find(hash)
            if (handle != null && handle.isValid()) return handle
            Thread.sleep(500)
        }
        return null
    }

    private fun monitorTorrent(handle: TorrentHandle, name: String) {
        while (handle.isValid()) {
            val status = handle.status(true)
            val percent = (status.progress() * 100f).toInt().coerceIn(0, 100)
            val speed = status.downloadRate()
            val peers = status.numPeers()
            notify("Torrent • $name • ${formatRate(speed)} • $peers peers", percent, true)

            if (percent >= 100 || (status.totalWanted() > 0 && status.totalWantedDone() >= status.totalWanted())) {
                notify("Torrent completed: $name", 100, false)
                try { torrentSession.remove(handle) } catch (_: Throwable) { }
                return
            }
            Thread.sleep(1000)
        }
        throw IllegalStateException("Torrent handle stopped")
    }

    private fun formatRate(bytesPerSecond: Int): String {
        val b = bytesPerSecond.toLong()
        return when {
            b >= 1024L * 1024L -> "%.1f MB/s".format(b / 1024.0 / 1024.0)
            b >= 1024L -> "%.0f KB/s".format(b / 1024.0)
            else -> "$b B/s"
        }
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(text: String, percent: Int, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(text, percent, ongoing)
        )
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Palia Download Manager")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(if (percent > 0) 100 else 0, percent.coerceIn(0, 100), percent <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PDM Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        Thread { try { torrentSession.stop() } catch (_: Throwable) { } }.start()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun PdmStorage.uniqueDirectory(parent: File, requestedName: String): File {
    parent.mkdirs()
    val safe = requestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "torrent" }
    var result = File(parent, safe)
    if (!result.exists()) return result
    var index = 1
    while (result.exists()) {
        result = File(parent, "$safe ($index)")
        index++
    }
    return result
}

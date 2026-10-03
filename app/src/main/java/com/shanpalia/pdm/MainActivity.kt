package com.shanpalia.pdm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val Mint = Color(0xFFE8FFF5)
private val Green = Color(0xFF16B978)
private val FileExtensions = listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = extractUrl(intent)
        setContent { PdmApp(this, incoming, { url -> startDownload(url) }) }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        return intent.dataString ?: if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
    }

    private fun startDownload(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        val name = URLUtil.guessFileName(clean, null, null)
        val serviceIntent = Intent(this, DownloadService::class.java).apply {
            putExtra("url", clean)
            putExtra("name", name)
        }
        startForegroundService(serviceIntent)
    }
}

@Composable
fun PdmApp(context: Context, incomingUrl: String?, onDownloadUrl: (String) -> Unit) {
    var url by remember { mutableStateOf(incomingUrl ?: "") }
    var selected by remember { mutableStateOf(if (incomingUrl != null && isLikelyFileUrl(incomingUrl)) "Home" else "Home") }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var downloadDialogUrl by remember { mutableStateOf(if (incomingUrl != null && isLikelyFileUrl(incomingUrl)) normalizeUrl(incomingUrl) else null) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White)) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home", "Downloads", "Browser", "Settings").forEach { item ->
                    NavigationBarItem(selected = selected == item, onClick = { selected = item }, icon = { Text(item.take(1)) }, label = { Text(item) })
                }
            }
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(url, { url = it }, {
                        val value = url.trim()
                        if (value.isNotBlank()) {
                            if (isLikelyFileUrl(value)) downloadDialogUrl = normalizeUrl(value)
                            else { browserUrl = normalizeUrl(value); selected = "Browser" }
                        }
                    })
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(browserUrl) { link -> downloadDialogUrl = link }
                    else -> SettingsScreen()
                }
                downloadDialogUrl?.let { link ->
                    AlertDialog(
                        onDismissRequest = { downloadDialogUrl = null },
                        title = { Text("Download File") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(URLUtil.guessFileName(link, null, null), fontWeight = FontWeight.Bold)
                            Text(link, color = Color.Gray)
                            Text("PDM will download this file in the background using multiple connections when the server supports HTTP ranges.")
                        } },
                        confirmButton = { Button(onClick = { onDownloadUrl(link); downloadDialogUrl = null }) { Text("Download") } },
                        dismissButton = { TextButton(onClick = { downloadDialogUrl = null }) { Text("Cancel") } }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(url: String, onUrl: (String) -> Unit, onGo: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("PDM", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Green); Text("Palia Download Manager", color = Color.Gray) }
        item { OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Paste download link or website") }, trailingIcon = { TextButton(onClick = onGo) { Text("GO") } }) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Smart Download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Paste a direct file URL to open the download page. Normal URLs open in the built-in browser."); Button(onClick = onGo) { Text("Open") } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Stat("Downloading", "—", Modifier.weight(1f)); Stat("Completed", "—", Modifier.weight(1f)); Stat("Failed", "—", Modifier.weight(1f)) } }
        item { Text("Recent Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("Your downloads appear in the Downloads tab and Android notification.", color = Color.Gray) }
    }
}

@Composable
fun BrowserScreen(initialUrl: String, onDownload: (String) -> Unit) {
    AndroidView(Modifier.fillMaxSize(), factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (isLikelyFileUrl(url)) { onDownload(url); return true }
                    return false
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener { url, _, _, _, _ -> onDownload(url) }
            loadUrl(initialUrl)
        }
    }, update = { view -> if (initialUrl.isNotBlank() && view.url != initialUrl) view.loadUrl(initialUrl) })
}

@Composable
fun DownloadsScreen(context: Context) {
    val manager = remember { context.getSystemService(android.app.DownloadManager::class.java) }
    var items by remember { mutableStateOf(listOf<DownloadItem>()) }
    LaunchedEffect(Unit) {
        val cursor = manager.query(android.app.DownloadManager.Query())
        val result = mutableListOf<DownloadItem>()
        cursor?.use {
            val idCol = it.getColumnIndex(android.app.DownloadManager.COLUMN_ID); val titleCol = it.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE); val statusCol = it.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS); val totalCol = it.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES); val doneCol = it.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (it.moveToNext()) result += DownloadItem(it.getLong(idCol), it.getString(titleCol) ?: "Download", statusText(it.getInt(statusCol)), it.getLong(totalCol), it.getLong(doneCol))
        }
        items = result.reversed()
    }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (items.isEmpty()) item { Text("New PDM downloads will appear here.", color = Color.Gray) }
        items(items.size) { index -> val item = items[index]; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(item.title, fontWeight = FontWeight.Bold); Text(item.status, color = Color.Gray); if (item.total > 0) { val p = (item.done.toFloat() / item.total).coerceIn(0f, 1f); LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()); Text("${(p * 100).toInt()}%") } } } }
    }
}

data class DownloadItem(val id: Long, val title: String, val status: String, val total: Long, val done: Long)
fun statusText(status: Int): String = when (status) { android.app.DownloadManager.STATUS_PENDING -> "Pending"; android.app.DownloadManager.STATUS_RUNNING -> "Downloading"; android.app.DownloadManager.STATUS_PAUSED -> "Paused"; android.app.DownloadManager.STATUS_SUCCESSFUL -> "Completed"; android.app.DownloadManager.STATUS_FAILED -> "Failed"; else -> "Unknown" }
fun normalizeUrl(value: String): String = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
fun isLikelyFileUrl(value: String): Boolean { val path = value.substringBefore('?').lowercase(); return FileExtensions.any { path.endsWith(it) } }

@Composable fun Stat(title: String, value: String, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(14.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(title, color = Color.Gray) } } }
@Composable fun SettingsScreen() { Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); SettingRow("Downloads", "Multi-connection, retry and download location"); SettingRow("Browser", "History, cache, JavaScript and desktop site"); SettingRow("App Update", "Check for the latest PDM release on GitHub"); SettingRow("About PDM", "PDM 1.0.0 • Developer By Shanpalia") } }
@Composable fun SettingRow(title: String, subtitle: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray) } } }

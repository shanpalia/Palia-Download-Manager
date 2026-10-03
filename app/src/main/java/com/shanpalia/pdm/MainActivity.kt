package com.shanpalia.pdm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
private val FileExtensions = listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".exe", ".msi", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")

class MainActivity : ComponentActivity() {
    private var pendingDownloadUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                context = this,
                onDownloadUrl = { url -> enqueueDownload(url) }
            )
        }
    }

    private fun enqueueDownload(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        val request = DownloadManager.Request(Uri.parse(clean)).apply {
            setTitle(URLUtil.guessFileName(clean, null, null))
            setDescription("PDM Download Manager")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(clean, null, null)
            )
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
    }
}

@Composable
fun PdmApp(context: Context, onDownloadUrl: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("Home") }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var downloadDialogUrl by remember { mutableStateOf<String?>(null) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White)) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home", "Downloads", "Browser", "Settings").forEach { item ->
                    NavigationBarItem(
                        selected = selected == item,
                        onClick = { selected = item },
                        icon = { Text(item.take(1)) },
                        label = { Text(item) }
                    )
                }
            }
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(
                        url = url,
                        onUrl = { url = it },
                        onGo = {
                            val value = url.trim()
                            if (value.isNotBlank()) {
                                if (isLikelyFileUrl(value)) downloadDialogUrl = normalizeUrl(value)
                                else { browserUrl = normalizeUrl(value); selected = "Browser" }
                            }
                        }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(
                        initialUrl = browserUrl,
                        onDownload = { link -> downloadDialogUrl = link }
                    )
                    else -> SettingsScreen()
                }

                downloadDialogUrl?.let { link ->
                    AlertDialog(
                        onDismissRequest = { downloadDialogUrl = null },
                        title = { Text("Download File") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(URLUtil.guessFileName(link, null, null), fontWeight = FontWeight.Bold)
                                Text(link, color = Color.Gray)
                                Text("PDM will save this file in your Downloads folder.")
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                onDownloadUrl(link)
                                downloadDialogUrl = null
                            }) { Text("Download") }
                        },
                        dismissButton = {
                            TextButton(onClick = { downloadDialogUrl = null }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(url: String, onUrl: (String) -> Unit, onGo: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("PDM", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Green)
            Text("Palia Download Manager", color = Color.Gray)
        }
        item {
            OutlinedTextField(
                value = url,
                onValueChange = onUrl,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Paste download link or website") },
                trailingIcon = { TextButton(onClick = onGo) { Text("GO") } }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Mint),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ready to download?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Paste a direct file URL. PDM will open a download confirmation page.")
                    Button(onClick = onGo) { Text("Add Download") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Downloading", "—", Modifier.weight(1f))
                Stat("Completed", "—", Modifier.weight(1f))
                Stat("Failed", "—", Modifier.weight(1f))
            }
        }
        item { Text("Recent Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("Your downloads will appear in the Downloads tab.", color = Color.Gray) }
    }
}

@Composable
fun BrowserScreen(initialUrl: String, onDownload: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    onDownload(url)
                }
                loadUrl(initialUrl)
            }
        },
        update = { view ->
            if (view.url != initialUrl && initialUrl.isNotBlank()) view.loadUrl(initialUrl)
        }
    )
}

@Composable
fun DownloadsScreen(context: Context) {
    val manager = remember { context.getSystemService(DownloadManager::class.java) }
    var items by remember { mutableStateOf(listOf<DownloadItem>()) }

    LaunchedEffect(Unit) {
        val query = DownloadManager.Query()
        val cursor = manager.query(query)
        val result = mutableListOf<DownloadItem>()
        cursor?.use {
            val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val doneCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (it.moveToNext()) {
                result += DownloadItem(
                    id = it.getLong(idCol),
                    title = it.getString(titleCol) ?: "Download",
                    status = statusText(it.getInt(statusCol)),
                    total = it.getLong(totalCol),
                    done = it.getLong(doneCol)
                )
            }
        }
        items = result.reversed()
    }

    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (items.isEmpty()) item { Text("No downloads yet", color = Color.Gray) }
        items(items.size) { index ->
            val item = items[index]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, fontWeight = FontWeight.Bold)
                    Text(item.status, color = Color.Gray)
                    if (item.total > 0) {
                        val progress = (item.done.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%")
                    }
                }
            }
        }
    }
}

data class DownloadItem(val id: Long, val title: String, val status: String, val total: Long, val done: Long)

fun statusText(status: Int): String = when (status) {
    DownloadManager.STATUS_PENDING -> "Pending"
    DownloadManager.STATUS_RUNNING -> "Downloading"
    DownloadManager.STATUS_PAUSED -> "Paused"
    DownloadManager.STATUS_SUCCESSFUL -> "Completed"
    DownloadManager.STATUS_FAILED -> "Failed"
    else -> "Unknown"
}

fun normalizeUrl(value: String): String = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"

fun isLikelyFileUrl(value: String): Boolean {
    val path = value.substringBefore('?').lowercase()
    return FileExtensions.any { path.endsWith(it) }
}

@Composable
fun Stat(title: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(title, color = Color.Gray)
        }
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        SettingRow("Downloads", "Download location, connections and retry")
        SettingRow("Browser", "History, cache and browser options")
        SettingRow("App Update", "Check for the latest PDM version")
        SettingRow("About PDM", "PDM 1.0.0 • Developer By Shanpalia")
    }
}

@Composable
fun SettingRow(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray)
        }
    }
}

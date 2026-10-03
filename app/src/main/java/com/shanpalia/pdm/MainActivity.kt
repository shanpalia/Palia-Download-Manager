package com.shanpalia.pdm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrowserUpdated
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Mint = Color(0xFFE9FFF6)
private val MintStrong = Color(0xFFCBF9E7)
private val Green = Color(0xFF12B878)
private val Dark = Color(0xFF10231D)
private val Muted = Color(0xFF71807A)
private const val CURRENT_VERSION = "1.0.0"
private val FileExtensions = listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        val incoming = extractUrl(intent)
        setContent { PdmApp(this, incoming) { startDownload(it) } }
    }
    private fun extractUrl(intent: Intent?): String? = intent?.dataString ?: if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
    private fun startDownload(url: String) { val clean = url.trim(); if (!clean.startsWith("http://") && !clean.startsWith("https://")) return; startForegroundService(Intent(this, DownloadService::class.java).apply { putExtra("url", clean); putExtra("name", URLUtil.guessFileName(clean, null, null)) }) }
}

@Composable
fun PdmApp(context: Context, incomingUrl: String?, onDownloadUrl: (String) -> Unit) {
    var showSplash by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf(incomingUrl ?: "") }
    var selected by remember { mutableStateOf("Home") }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var dialogUrl by remember { mutableStateOf(if (incomingUrl != null && isLikelyFileUrl(incomingUrl)) normalizeUrl(incomingUrl) else null) }
    LaunchedEffect(Unit) { delay(1200); showSplash = false }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (showSplash) {
            SplashScreen()
            return@MaterialTheme
        }
        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 10.dp) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Web, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    ).forEach { (item, icon, label) ->
                        NavigationBarItem(selected = selected == item, onClick = { selected = item }, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(url, { url = it }, {
                        val value = url.trim()
                        if (value.isNotBlank()) {
                            if (isLikelyFileUrl(value)) dialogUrl = normalizeUrl(value)
                            else { browserUrl = normalizeUrl(value); selected = "Browser" }
                        }
                    }, onBrowser = { selected = "Browser" }, onDownloads = { selected = "Downloads" })
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(browserUrl) { link -> dialogUrl = link }
                    else -> SettingsScreen()
                }
                dialogUrl?.let { link ->
                    AlertDialog(onDismissRequest = { dialogUrl = null }, title = { Text("Download File") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(URLUtil.guessFileName(link, null, null), fontWeight = FontWeight.Bold); Text(link, color = Muted); Text("PDM will download in the background. Multi-connection is used when the server supports HTTP ranges.") } }, confirmButton = { Button(onClick = { onDownloadUrl(link); dialogUrl = null }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") } }, dismissButton = { TextButton({ dialogUrl = null }) { Text("Cancel") } })
                }
            }
        }
    }
}

@Composable private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            PdmLogo(118)
            Text("PDM", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Green)
            Text("Palia Download Manager", color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(color = Green, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text("Developer By Shanpalia", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable private fun PdmLogo(size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(Green), contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * .72).dp).clip(CircleShape).background(Color.White.copy(alpha = .96f)), contentAlignment = Alignment.Center) {
            Text("P", fontSize = (size * .34).sp, fontWeight = FontWeight.Black, color = Green)
        }
    }
}

@Composable private fun HomeScreen(url: String, onUrl: (String) -> Unit, onGo: () -> Unit, onBrowser: () -> Unit, onDownloads: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PdmLogo(54)
                Spacer(Modifier.width(12.dp))
                Column { Text("Palia Download Manager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Dark); Text("Fast • Smart • Simple", color = Muted) }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Bolt, null, tint = Green); Spacer(Modifier.width(8.dp)); Text("Smart Download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), placeholder = { Text("Paste URL or search") }, trailingIcon = { IconButton(onClick = onGo) { Icon(Icons.Default.ArrowForward, "Go", tint = Green) } })
                    Text("Direct file links open the download page. Website links open in PDM Browser.", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Browser", Icons.Default.Web, onBrowser, Modifier.weight(1f))
                QuickAction("Downloads", Icons.Default.Download, onDownloads, Modifier.weight(1f))
            }
        }
        item {
            Text("Download Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Dark)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Active", "0", Icons.Default.Speed, Modifier.weight(1f)); Stat("Done", "0", Icons.Default.DownloadDone, Modifier.weight(1f)); Stat("Failed", "0", Icons.Default.Update, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8F8)), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.BrowserUpdated, null, tint = Green, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp)); Column { Text("Built-in Browser", fontWeight = FontWeight.Bold); Text("Browse and send downloads directly to PDM.", color = Muted, fontSize = 13.sp) } }
            }
        }
        item { Text("PDM  •  Developer By Shanpalia", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable private fun QuickAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = Green); Text(title, fontWeight = FontWeight.Bold) } }
}

@Composable private fun Stat(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F6F6))) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp)); Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold); Text(title, color = Muted, fontSize = 12.sp) } }
}

@Composable fun BrowserScreen(initialUrl: String, onDownload: (String) -> Unit) {
    AndroidView(factory = { ctx: Context -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.loadsImagesAutomatically = true; settings.allowFileAccess = false; webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean { if (isLikelyFileUrl(url)) { onDownload(url); return true }; return false } }; webChromeClient = WebChromeClient(); setDownloadListener { url, _, _, _, _ -> onDownload(url) }; loadUrl(initialUrl) } }, modifier = Modifier.fillMaxSize(), update = { view -> if (initialUrl.isNotBlank() && view.url != initialUrl) view.loadUrl(initialUrl) })
}

@Composable fun DownloadsScreen(context: Context) {
    val manager = remember { context.getSystemService(android.app.DownloadManager::class.java) }; var items by remember { mutableStateOf(listOf<DownloadItem>()) }
    LaunchedEffect(Unit) { val cursor = manager.query(android.app.DownloadManager.Query()); val result = mutableListOf<DownloadItem>(); cursor?.use { val id = it.getColumnIndex(android.app.DownloadManager.COLUMN_ID); val title = it.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE); val status = it.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS); val total = it.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES); val done = it.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR); while (it.moveToNext()) result += DownloadItem(it.getLong(id), it.getString(title) ?: "Download", statusText(it.getInt(status)), it.getLong(total), it.getLong(done)) }; items = result.reversed() }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; if (items.isEmpty()) item { Text("No downloads yet.", color = Muted) }; items(items.size) { index -> val item = items[index]; Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(item.title, fontWeight = FontWeight.Bold); Text(item.status, color = Muted); if (item.total > 0) { val p = (item.done.toFloat() / item.total).coerceIn(0f, 1f); LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()); Text("${(p * 100).toInt()}%") } } } } }
}

data class DownloadItem(val id: Long, val title: String, val status: String, val total: Long, val done: Long)
fun statusText(status: Int): String = when (status) { android.app.DownloadManager.STATUS_PENDING -> "Pending"; android.app.DownloadManager.STATUS_RUNNING -> "Downloading"; android.app.DownloadManager.STATUS_PAUSED -> "Paused"; android.app.DownloadManager.STATUS_SUCCESSFUL -> "Completed"; android.app.DownloadManager.STATUS_FAILED -> "Failed"; else -> "Unknown" }
fun normalizeUrl(value: String): String = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
fun isLikelyFileUrl(value: String): Boolean { val path = value.substringBefore('?').lowercase(); return FileExtensions.any { path.endsWith(it) } }

@Composable fun SettingsScreen() {
    var status by remember { mutableStateOf("Not checked") }; val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); SettingRow("Downloads", "Multi-connection, retry and background service"); SettingRow("Browser", "History, cache, JavaScript and desktop site"); Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("App Update", fontWeight = FontWeight.Bold); Text("Current version $CURRENT_VERSION", color = Muted); Text(status, color = Muted); Button(onClick = { status = "Checking…"; scope.launch { status = checkLatestRelease() } }) { Text("Check for Updates") } } }; SettingRow("About PDM", "PDM $CURRENT_VERSION • Developer By Shanpalia") }
}

suspend fun checkLatestRelease(): String = withContext(Dispatchers.IO) { try { val c = URL("https://api.github.com/repos/shanpalia/Palia-Download-Manager/releases/latest").openConnection() as HttpURLConnection; c.setRequestProperty("Accept", "application/vnd.github+json"); c.connectTimeout = 8000; c.readTimeout = 8000; val body = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect(); val tag = Regex("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)?.removePrefix("v"); when { tag == null -> "No release found"; tag == CURRENT_VERSION -> "You are up to date"; else -> "New version available: $tag" } } catch (e: Exception) { "Update check failed: ${e.message ?: "network error"}" } }
@Composable fun SettingRow(title: String, subtitle: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted) } } }

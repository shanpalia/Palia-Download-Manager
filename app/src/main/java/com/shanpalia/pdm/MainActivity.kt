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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
private val MintStrong = Color(0xFFCFF8E9)
private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF0B62D6)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Soft = Color(0xFFF5F8F7)
private const val CURRENT_VERSION = "1.0.0"
private val FileExtensions = listOf(".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
        val incoming = extractUrl(intent)
        setContent { PdmApp(this, incoming) { startDownload(it) } }
    }

    private fun extractUrl(intent: Intent?): String? = intent?.dataString ?: if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null

    private fun startDownload(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        startForegroundService(Intent(this, DownloadService::class.java).apply {
            putExtra("url", clean)
            putExtra("name", URLUtil.guessFileName(clean, null, null))
        })
    }
}

@Composable
fun PdmApp(context: Context, incomingUrl: String?, onDownloadUrl: (String) -> Unit) {
    var showSplash by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf(incomingUrl ?: "") }
    var selected by remember { mutableStateOf("Home") }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var dialogUrl by remember { mutableStateOf(if (incomingUrl != null && isLikelyFileUrl(incomingUrl)) normalizeUrl(incomingUrl) else null) }

    LaunchedEffect(Unit) { delay(2200); showSplash = false }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (showSplash) {
            SplashScreen()
            return@MaterialTheme
        }

        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Web, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    ).forEach { (item, icon, label) ->
                        NavigationBarItem(
                            selected = selected == item,
                            onClick = { selected = item },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Dark,
                                selectedTextColor = Dark,
                                indicatorColor = Color(0xFFE5DDF7),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (selected) {
                    "Home" -> HomeScreen(
                        url = url,
                        onUrl = { url = it },
                        onGo = {
                            val value = url.trim()
                            if (value.isNotBlank()) {
                                if (isLikelyFileUrl(value)) dialogUrl = normalizeUrl(value)
                                else { browserUrl = normalizeUrl(value); selected = "Browser" }
                            }
                        },
                        onBrowser = { selected = "Browser" },
                        onDownloads = { selected = "Downloads" },
                        onAddDownload = {
                            val value = url.trim()
                            if (value.isNotBlank()) dialogUrl = normalizeUrl(value)
                        }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(browserUrl) { link -> dialogUrl = link }
                    else -> SettingsScreen()
                }

                dialogUrl?.let { link ->
                    AlertDialog(
                        onDismissRequest = { dialogUrl = null },
                        title = { Text("Download File") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(URLUtil.guessFileName(link, null, null), fontWeight = FontWeight.Bold)
                                Text(link, color = Muted)
                                Text("PDM will download in the background.")
                            }
                        },
                        confirmButton = {
                            Button(onClick = { onDownloadUrl(link); dialogUrl = null }) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Download")
                            }
                        },
                        dismissButton = { TextButton({ dialogUrl = null }) { Text("Cancel") } }
                    )
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_pdm_logo),
                contentDescription = "PDM",
                modifier = Modifier.size(150.dp),
                contentScale = ContentScale.Fit
            )
            Text("PDM", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Green)
            Text("Palia Download Manager", color = Muted, fontSize = 18.sp)
            Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)),
                color = Green,
                trackColor = Color(0xFFE8EEF0)
            )
            Text("Loading…", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text("Developer By", color = Muted, fontSize = 12.sp)
            Text("Shanpalia", color = Blue, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PdmLogo(size: Int) {
    Image(
        painter = painterResource(R.drawable.ic_pdm_logo),
        contentDescription = "PDM logo",
        modifier = Modifier.size(size.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun HomeScreen(
    url: String,
    onUrl: (String) -> Unit,
    onGo: () -> Unit,
    onBrowser: () -> Unit,
    onDownloads: () -> Unit,
    onAddDownload: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PdmLogo(62)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Palia Download Manager", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                        Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp)
                    }
                    IconButton(onClick = { }) { Icon(Icons.Default.Search, "Search", tint = Dark) }
                    IconButton(onClick = { }) { Icon(Icons.Default.MoreVert, "More", tint = Dark) }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = onUrl,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            placeholder = { Text("Paste URL or search the web") },
                            leadingIcon = { Icon(Icons.Default.Language, null, tint = Dark) },
                            trailingIcon = {
                                FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                                    Icon(Icons.Default.ArrowForward, "Go", tint = Color.White)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PdmLogo(72)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Ready to download?", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                                Text("Paste a direct link and download faster.", color = Muted, fontSize = 14.sp)
                            }
                        }
                        Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Download", fontWeight = FontWeight.Bold)
                        }
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
                Text("Download Center", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Stat("Downloading", "0", Icons.Default.Download, Modifier.weight(1f))
                    Stat("Completed", "0", Icons.Default.CheckCircle, Modifier.weight(1f))
                    Stat("Failed", "0", Icons.Default.ErrorOutline, Modifier.weight(1f))
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent Downloads", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Dark, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDownloads) { Text("See All") }
                        }
                        Spacer(Modifier.height(18.dp))
                        Icon(Icons.Default.DownloadDone, null, tint = Green, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No downloads yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Paste a link above or browse the web to start downloading.", color = Muted, fontSize = 13.sp)
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FBFA)), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, null, tint = Green, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Smart Download Engine", fontWeight = FontWeight.Bold)
                            Text("Background downloads with Android notifications.", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        FloatingActionButton(onClick = onAddDownload, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp), containerColor = Blue, contentColor = Color.White) {
            Icon(Icons.Default.Download, "Add download")
        }
    }
}

@Composable
private fun QuickAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(30.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Stat(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text(title, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun BrowserScreen(initialUrl: String, onDownload: (String) -> Unit) {
    AndroidView<WebView>(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (isLikelyFileUrl(url)) {
                            onDownload(url)
                            return true
                        }
                        return false
                    }
                }
                webChromeClient = WebChromeClient()
                setDownloadListener { downloadUrl, _, _, _, _ -> onDownload(downloadUrl) }
                loadUrl(initialUrl)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            if (initialUrl.isNotBlank() && view.url != initialUrl) view.loadUrl(initialUrl)
        }
    )
}

@Composable
fun DownloadsScreen(context: Context) {
    val manager = remember { context.getSystemService(android.app.DownloadManager::class.java) }
    var items by remember { mutableStateOf(listOf<DownloadItem>()) }
    LaunchedEffect(Unit) {
        val cursor = manager.query(android.app.DownloadManager.Query())
        val result = mutableListOf<DownloadItem>()
        cursor?.use {
            val id = it.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
            val title = it.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
            val status = it.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
            val total = it.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val done = it.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (it.moveToNext()) result += DownloadItem(it.getLong(id), it.getString(title) ?: "Download", statusText(it.getInt(status)), it.getLong(total), it.getLong(done))
        }
        items = result.reversed()
    }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (items.isEmpty()) item { Text("No downloads yet.", color = Muted) }
        items(items.size) { index ->
            val item = items[index]
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, fontWeight = FontWeight.Bold)
                    Text(item.status, color = Muted)
                    if (item.total > 0) {
                        val p = (item.done.toFloat() / item.total).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}%")
                    }
                }
            }
        }
    }
}

data class DownloadItem(val id: Long, val title: String, val status: String, val total: Long, val done: Long)

fun statusText(status: Int): String = when (status) {
    android.app.DownloadManager.STATUS_PENDING -> "Pending"
    android.app.DownloadManager.STATUS_RUNNING -> "Downloading"
    android.app.DownloadManager.STATUS_PAUSED -> "Paused"
    android.app.DownloadManager.STATUS_SUCCESSFUL -> "Completed"
    android.app.DownloadManager.STATUS_FAILED -> "Failed"
    else -> "Unknown"
}

fun normalizeUrl(value: String): String = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
fun isLikelyFileUrl(value: String): Boolean { val path = value.substringBefore('?').lowercase(); return FileExtensions.any { path.endsWith(it) } }

@Composable
fun SettingsScreen() {
    var status by remember { mutableStateOf("Not checked") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        SettingRow("Downloads", "Multi-connection, retry and background service")
        SettingRow("Browser", "History, cache, JavaScript and desktop site")
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App Update", fontWeight = FontWeight.Bold)
                Text("Current version $CURRENT_VERSION", color = Muted)
                Text(status, color = Muted)
                Button(onClick = { status = "Checking…"; scope.launch { status = checkLatestRelease() } }) { Text("Check for Updates") }
            }
        }
        SettingRow("About PDM", "PDM $CURRENT_VERSION • Developer By Shanpalia")
    }
}

suspend fun checkLatestRelease(): String = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://api.github.com/repos/shanpalia/Palia-Download-Manager/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val tag = Regex("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)?.removePrefix("v")
        when { tag == null -> "No release found"; tag == CURRENT_VERSION -> "You are up to date"; else -> "New version available: $tag" }
    } catch (e: Exception) {
        "Update check failed: ${e.message ?: "network error"}"
    }
}

@Composable
fun SettingRow(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted)
        }
    }
}

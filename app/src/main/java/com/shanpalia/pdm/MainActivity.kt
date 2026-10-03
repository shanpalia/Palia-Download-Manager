package com.shanpalia.pdm

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val Mint = Color(0xFFE9FFF6)
private val MintStrong = Color(0xFFCFF8E9)
private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF0B62D6)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Soft = Color(0xFFF5F8F7)
private const val CURRENT_VERSION = "1.1.0"

private data class HistoryItem(val url: String, val time: Long)

private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    private val key = "items"

    fun all(): List<HistoryItem> {
        val array = try { JSONArray(prefs.getString(key, "[]") ?: "[]") } catch (_: Throwable) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isNotBlank()) add(HistoryItem(url, item.optLong("time")))
            }
        }
    }

    fun add(value: String) {
        val url = value.trim()
        if (url.isBlank()) return
        val list = all().filterNot { it.url == url }.toMutableList()
        list.add(0, HistoryItem(url, System.currentTimeMillis()))
        val array = JSONArray()
        list.take(100).forEach { item ->
            array.put(JSONObject().apply { put("url", item.url); put("time", item.time) })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun remove(url: String) {
        val array = JSONArray()
        all().filterNot { it.url == url }.forEach { item ->
            array.put(JSONObject().apply { put("url", item.url); put("time", item.time) })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun clear() { prefs.edit().remove(key).apply() }
}

class MainActivity : ComponentActivity() {
    private var pendingLink: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyTorrent(uri)?.let { startTorrentFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                context = this,
                incomingUrl = extractIncoming(intent),
                onDownload = ::startUrlDownload,
                onPickTorrent = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                onStorageSettings = ::openStorageSettings,
                requestStorage = { link ->
                    pendingLink = link
                    waitingForStorage = true
                    openStorageSettings()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val link = pendingLink
            pendingLink = null
            waitingForStorage = false
            if (!link.isNullOrBlank()) startUrlDownload(link)
        }
    }

    private fun extractIncoming(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND) return intent.getStringExtra(Intent.EXTRA_TEXT)
        val data = intent?.data ?: return null
        return if (data.scheme == "http" || data.scheme == "https" || data.scheme == "magnet") data.toString() else null
    }

    private fun startUrlDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:?", true) -> startService(DownloadService.ACTION_TORRENT_MAGNET, clean, null)
            clean.substringBefore('?').lowercase().endsWith(".torrent") -> startService(DownloadService.ACTION_TORRENT_URL, clean, null)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> startService(DownloadService.ACTION_HTTP, clean, null)
            else -> startService(DownloadService.ACTION_HTTP, "https://www.google.com/search?q=${Uri.encode(clean)}", null)
        }
    }

    private fun startTorrentFile(file: File) {
        startService(DownloadService.ACTION_TORRENT_FILE, null, file.absolutePath)
    }

    private fun startService(action: String, url: String?, path: String?) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun copyTorrent(uri: Uri): File? = try {
        File(cacheDir, "selected-${System.currentTimeMillis()}.torrent").also { target ->
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    } catch (_: Throwable) { null }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}

@Composable
private fun PdmApp(
    context: Context,
    incomingUrl: String?,
    onDownload: (String) -> Unit,
    onPickTorrent: () -> Unit,
    onStorageSettings: () -> Unit,
    requestStorage: (String) -> Unit
) {
    val history = remember(context) { HistoryStore(context) }
    var historyItems by remember { mutableStateOf(history.all()) }
    var splash by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf("Home") }
    var url by remember { mutableStateOf(incomingUrl.orEmpty()) }
    var browserUrl by remember { mutableStateOf(incomingUrl?.takeIf { it.startsWith("http", true) } ?: "https://www.google.com") }
    var downloadDialog by remember { mutableStateOf<String?>(null) }
    var storageDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(2200)
        splash = false
    }

    LaunchedEffect(splash) {
        if (!splash && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            (context as? ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    fun record(value: String) {
        history.add(value)
        historyItems = history.all()
    }

    fun beginDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        record(clean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            storageDialog = clean
        } else {
            downloadDialog = clean
        }
    }

    fun openInput(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        if (clean.startsWith("magnet:", true) || isFileUrl(clean)) {
            beginDownload(clean)
        } else if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            record(clean)
            browserUrl = clean
            selected = "Browser"
        } else {
            record(clean)
            browserUrl = "https://www.google.com/search?q=${Uri.encode(clean)}"
            selected = "Browser"
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) {
            SplashScreen()
            return@MaterialTheme
        }

        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFF4FFFB), tonalElevation = 0.dp) {
                    val tabs = listOf(
                        Triple("Home", Icons.Default.Home, "Home"),
                        Triple("Downloads", Icons.Default.Download, "Downloads"),
                        Triple("Browser", Icons.Default.Web, "Browser"),
                        Triple("Settings", Icons.Default.Settings, "Settings")
                    )
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab.first,
                            onClick = { selected = tab.first },
                            icon = { Icon(imageVector = tab.second, contentDescription = tab.third) },
                            label = { Text(tab.third) },
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
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    "Home" -> HomeScreen(
                        context = context,
                        url = url,
                        onUrl = { url = it },
                        onGo = { openInput(url) },
                        onBrowser = { selected = "Browser" },
                        onDownloads = { selected = "Downloads" },
                        onTorrent = onPickTorrent,
                        onAddDownload = { beginDownload(url) },
                        historyItems = historyItems,
                        onHistoryClick = { value -> url = value; openInput(value) },
                        onHistoryDelete = { value -> history.remove(value); historyItems = history.all() },
                        onClearHistory = { history.clear(); historyItems = emptyList() }
                    )
                    "Downloads" -> DownloadsScreen(context)
                    "Browser" -> BrowserScreen(
                        context = context,
                        initialUrl = browserUrl,
                        historyItems = historyItems,
                        onVisit = ::record,
                        onDownload = ::beginDownload,
                        onOpen = { browserUrl = normalizeBrowserUrl(it) }
                    )
                    else -> SettingsScreen(
                        context = context,
                        onStorageSettings = onStorageSettings,
                        historyItems = historyItems,
                        onClearHistory = { history.clear(); historyItems = emptyList() }
                    )
                }
            }
        }

        downloadDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { downloadDialog = null },
                title = { Text(if (link.startsWith("magnet:", true)) "Add Torrent" else "Download File") },
                text = { Text(link, color = Muted) },
                confirmButton = {
                    Button(
                        onClick = { onDownload(link); downloadDialog = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (link.startsWith("magnet:", true)) "Start Torrent" else "Download")
                    }
                },
                dismissButton = { TextButton(onClick = { downloadDialog = null }) { Text("Cancel") } }
            )
        }

        storageDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { storageDialog = null },
                title = { Text("Storage access") },
                text = { Text("PDM can save downloads in Download/PDM with separate Images, Videos, Music, APK, ZIP, Documents and Torrents folders. Android will open the system access page only when you start a download.") },
                confirmButton = {
                    Button(
                        onClick = { storageDialog = null; requestStorage(link) },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Allow storage access") }
                },
                dismissButton = {
                    TextButton(onClick = { storageDialog = null; onDownload(link) }) { Text("Use app storage") }
                }
            )
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painter = painterResource(R.drawable.ic_pdm_logo), contentDescription = "PDM", modifier = Modifier.size(170.dp), contentScale = ContentScale.Fit)
            Text("PDM", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Green)
            Text("Palia Download Manager", color = Muted, fontSize = 18.sp)
            Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(modifier = Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE8EEF0))
            Text("Loading…", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text("Developer By", color = Muted, fontSize = 12.sp)
            Text("Shanpalia", color = Blue, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PdmLogo(size: Int) {
    Image(painter = painterResource(R.drawable.ic_pdm_logo), contentDescription = "PDM logo", modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit)
}

@Composable
private fun HomeScreen(
    context: Context,
    url: String,
    onUrl: (String) -> Unit,
    onGo: () -> Unit,
    onBrowser: () -> Unit,
    onDownloads: () -> Unit,
    onTorrent: () -> Unit,
    onAddDownload: () -> Unit,
    historyItems: List<HistoryItem>,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    LaunchedEffect(url) {
        if (field.text != url) field = TextFieldValue(url, TextRange(url.length))
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 105.dp),
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
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { value -> field = value; onUrl(value.text) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        placeholder = { Text("Paste URL, magnet or search") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = Dark) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (field.text.isNotBlank()) {
                                    IconButton(onClick = { field = TextFieldValue(""); onUrl("") }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                                TextButton(onClick = {
                                    val clip = readClipboard(context)
                                    if (clip.isNotBlank()) {
                                        field = TextFieldValue(clip, TextRange(clip.length))
                                        onUrl(clip)
                                    }
                                }) { Text("PASTE") }
                                FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Go", tint = Color.White)
                                }
                            }
                        }
                    )
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
                                Text("HTTP/HTTPS, .torrent and magnet links.", color = Muted, fontSize = 14.sp)
                            }
                        }
                        Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
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
                    QuickAction("Torrent", Icons.Default.Download, onTorrent, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent History", modifier = Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                    if (historyItems.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear all") }
                }
                if (historyItems.isEmpty()) {
                    Text("Your visited links and searches will appear here.", color = Muted, fontSize = 14.sp)
                } else {
                    historyItems.take(6).forEach { item ->
                        HistoryRow(item.url, onClick = { onHistoryClick(item.url) }, onDelete = { onHistoryDelete(item.url) })
                    }
                }
            }
            item {
                Text("Download Center", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Stat("Downloading", Icons.Default.Download, Modifier.weight(1f))
                    Stat("Completed", Icons.Default.CheckCircle, Modifier.weight(1f))
                    Stat("Failed", Icons.Default.ErrorOutline, Modifier.weight(1f))
                }
            }
        }
        FloatingActionButton(onClick = onAddDownload, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp), containerColor = Blue, contentColor = Color.White) {
            Icon(imageVector = Icons.Default.Download, contentDescription = "Add download")
        }
    }
}

@Composable
private fun HistoryRow(url: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = Green, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(10.dp))
            Text(url, modifier = Modifier.weight(1f), maxLines = 2, fontSize = 13.sp, color = Dark)
            IconButton(onClick = onDelete) { Icon(imageVector = Icons.Default.Clear, contentDescription = "Delete", tint = Muted) }
        }
    }
}

@Composable
private fun QuickAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, tint = Green, modifier = Modifier.size(29.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Stat(title: String, icon: ImageVector, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
        Column(Modifier.padding(13.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = Green, modifier = Modifier.size(22.dp))
            Text("0", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text(title, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            PdmStorage.ensureFolders(context).walkTopDown().filter { it.isFile }.take(200).toList()
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Downloads", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("PDM: ${PdmStorage.root(context).absolutePath}", color = Muted, fontSize = 12.sp)
        }
        if (files.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = Green, modifier = Modifier.size(56.dp))
                        Text("No downloads yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Downloaded files will appear here.", color = Muted)
                    }
                }
            }
        } else {
            items(files, key = { it.absolutePath }) { file ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = Green, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(file.name, fontWeight = FontWeight.Bold)
                            Text(file.parentFile?.name ?: "PDM", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserScreen(
    context: Context,
    initialUrl: String,
    historyItems: List<HistoryItem>,
    onVisit: (String) -> Unit,
    onDownload: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    var address by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var showHistory by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val webUrl = normalizeBrowserUrl(initialUrl)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { }) { Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back") }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; showHistory = false },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { state ->
                    if (state.isFocused && address.text.isNotBlank()) address = address.copy(selection = TextRange(0, address.text.length))
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search or enter address") },
                leadingIcon = { Icon(imageVector = Icons.Default.Language, contentDescription = null) },
                trailingIcon = {
                    TextButton(onClick = {
                        val clip = readClipboard(context)
                        if (clip.isNotBlank()) address = TextFieldValue(clip, TextRange(clip.length))
                        showHistory = false
                    }) { Text("PASTE") }
                }
            )
            Spacer(Modifier.width(5.dp))
            FilledIconButton(onClick = {
                val value = address.text.trim()
                if (value.isNotBlank()) {
                    onVisit(value)
                    onOpen(value)
                }
                showHistory = false
            }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Go", tint = Color.White)
            }
        }

        if (showHistory && historyItems.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("Recent history", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold, color = Dark)
                    historyItems.take(8).forEach { item ->
                        TextButton(onClick = {
                            address = TextFieldValue(item.url, TextRange(item.url.length))
                            onVisit(item.url)
                            onOpen(item.url)
                            showHistory = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(item.url, maxLines = 1, color = Dark)
                        }
                    }
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val link = request.url.toString()
                            onVisit(link)
                            if (isFileUrl(link) || link.startsWith("magnet:", true)) {
                                onDownload(link)
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            onVisit(url)
                        }
                    }
                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        onVisit(downloadUrl)
                        onDownload(downloadUrl)
                    }
                    loadUrl(webUrl)
                }
            },
            update = { view ->
                val desired = normalizeBrowserUrl(address.text)
                if (desired != view.url && desired.isNotBlank()) view.loadUrl(desired)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SettingsScreen(context: Context, onStorageSettings: () -> Unit, historyItems: List<HistoryItem>, onClearHistory: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Current version $CURRENT_VERSION") }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Downloads", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("HTTP resume, background service, torrent and magnet support.", color = Muted)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PDM Storage", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(PdmStorage.root(context).absolutePath, color = Muted, fontSize = 12.sp)
                    Button(onClick = onStorageSettings, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                        Icon(imageVector = Icons.Default.Storage, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Storage access")
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Browser History", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("${historyItems.size} saved entries", color = Muted)
                    Button(onClick = onClearHistory, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Clear history") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App Update", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(status, color = Muted)
                    Button(onClick = {
                        status = "Checking GitHub Releases…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { checkLatestRelease() }
                            status = result
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Check for Updates") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("About PDM", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Palia Download Manager")
                    Text("Version $CURRENT_VERSION • Developer By Shanpalia", color = Muted)
                }
            }
        }
    }
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

private fun normalizeBrowserUrl(value: String): String {
    val clean = value.trim()
    if (clean.isBlank()) return "https://www.google.com"
    if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
    if (clean.startsWith("magnet:", true) || isFileUrl(clean)) return "https://www.google.com"
    return "https://www.google.com/search?q=${Uri.encode(clean)}"
}

private fun isFileUrl(value: String): Boolean {
    val path = value.substringBefore('?').substringBefore('#').lowercase()
    val extensions = listOf(
        ".apk", ".zip", ".rar", ".7z", ".pdf", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".avi", ".mov",
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".iso", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".torrent"
    )
    return extensions.any { path.endsWith(it) }
}

private suspend fun checkLatestRelease(): String {
    return try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("https://api.github.com/repos/shanpalia/Palia-Download-Manager/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return "No published GitHub release yet."
            if (!response.isSuccessful) return "Update check failed: HTTP ${response.code}"
            val tag = JSONObject(response.body?.string().orEmpty()).optString("tag_name")
            if (tag.isBlank()) "No release version found."
            else if (tag.removePrefix("v") == CURRENT_VERSION) "You are up to date ($CURRENT_VERSION)."
            else "Latest release: $tag"
        }
    } catch (e: Throwable) {
        "Update check failed: ${e.message ?: "network error"}"
    }
}

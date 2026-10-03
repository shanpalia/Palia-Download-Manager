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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
private const val UPDATE_MANIFEST = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"
private const val WEBSITE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/"

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
            array.put(JSONObject().apply {
                put("url", item.url)
                put("time", item.time)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun remove(value: String) {
        val array = JSONArray()
        all().filterNot { it.url == value }.forEach { item ->
            array.put(JSONObject().apply {
                put("url", item.url)
                put("time", item.time)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun clear() = prefs.edit().remove(key).apply()
}

class MainActivity : ComponentActivity() {
    private var pendingLink: String? = null
    private var waitingForStorage = false

    private val torrentPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) copyTorrent(uri)?.let(::startTorrentFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdmApp(
                context = this,
                incomingUrl = extractIncoming(intent),
                onDownload = ::startUrlDownload,
                onPickTorrent = {
                    torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                },
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
        return if (data.scheme in listOf("http", "https", "magnet")) data.toString() else null
    }

    private fun startUrlDownload(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        when {
            clean.startsWith("magnet:", true) -> startService(DownloadService.ACTION_TORRENT_MAGNET, clean, null)
            clean.substringBefore('?').lowercase().endsWith(".torrent") -> startService(DownloadService.ACTION_TORRENT_URL, clean, null)
            clean.startsWith("http://", true) || clean.startsWith("https://", true) ->
                startService(DownloadService.ACTION_HTTP, clean, null)
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
    } catch (_: Throwable) {
        null
    }

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
    var browserUrl by remember {
        mutableStateOf(incomingUrl?.takeIf { it.startsWith("http", true) } ?: "https://www.google.com")
    }
    var storageDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(2200)
        splash = false
    }

    LaunchedEffect(splash) {
        if (!splash && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? ComponentActivity)?.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                501
            )
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
            onDownload(clean)
        }
    }

    fun openInput(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        if (clean.startsWith("magnet:", true) || isFileUrl(clean)) {
            beginDownload(clean)
        } else {
            record(clean)
            browserUrl = if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
                clean
            } else {
                "https://www.google.com/search?q=${Uri.encode(clean)}"
            }
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

        storageDialog?.let { link ->
            AlertDialog(
                onDismissRequest = { storageDialog = null },
                title = { Text("Storage access") },
                text = {
                    Text("PDM will save files in Download/PDM with separate Images, Videos, Music, APK, ZIP, Documents and Torrents folders. Android will open the system access page now.")
                },
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PdmLogo(210)
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("FAST  •  SMART  •  SECURE", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                modifier = Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)),
                color = Green,
                trackColor = Color(0xFFE8EEF0)
            )
            Text("Loading…", color = Muted, fontSize = 13.sp)
            Text("Developer By Shanpalia", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PdmLogo(size: Int) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_pdm_logo),
        contentDescription = "PDM",
        modifier = Modifier.size(size.dp)
    )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PdmLogo(72)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Palia Download Manager", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                        Text("Fast • Smart • Secure", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it; onUrl(it.text) },
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
                                TextButton(onClick = { field = TextFieldValue(readClipboard(context)); onUrl(field.text) }) {
                                    Text("PASTE", color = Green, fontWeight = FontWeight.Bold)
                                }
                                FilledIconButton(onClick = onGo, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue)) {
                                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Open")
                                }
                            }
                        }
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PdmLogo(105)
                            Spacer(Modifier.width(18.dp))
                            Column {
                                Text("Ready to download?", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
                                Text("HTTP/HTTPS, .torrent and magnet links.", color = Muted, fontSize = 16.sp)
                            }
                        }
                        Button(onClick = onAddDownload, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionCard("Browser", Icons.Default.Web, Modifier.weight(1f), onBrowser)
                    ActionCard("Downloads", Icons.Default.Download, Modifier.weight(1f), onDownloads)
                    ActionCard("Torrent", Icons.Default.Download, Modifier.weight(1f), onTorrent)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Recent History", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Dark, modifier = Modifier.weight(1f))
                    if (historyItems.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear") }
                }
            }
            if (historyItems.isEmpty()) {
                item {
                    Text("Your visited links and searches will appear here.", color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                }
            } else {
                items(historyItems.take(10), key = { it.url }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(item.url, maxLines = 2, modifier = Modifier.weight(1f), color = Dark)
                            IconButton(onClick = { onHistoryClick(item.url) }) { Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = "Open") }
                            IconButton(onClick = { onHistoryDelete(item.url) }) { Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete") }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onAddDownload, containerColor = Blue, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp, 18.dp, 18.dp, 82.dp)) {
            Icon(imageVector = Icons.Default.Download, contentDescription = "Add download")
        }
    }
}

@Composable
private fun ActionCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.height(100.dp), colors = CardDefaults.cardColors(containerColor = MintStrong), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = icon, contentDescription = title, tint = Green, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(7.dp))
            Text(title, fontWeight = FontWeight.Bold, color = Dark)
        }
    }
}

@Composable
private fun DownloadsScreen(context: Context) {
    val files = remember { mutableStateListOf<File>() }
    LaunchedEffect(Unit) { files.clear(); files.addAll(PdmStorage.allFiles(context)) }
    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Downloads", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Dark) }
        if (files.isEmpty()) item { Text("No downloads yet.", color = Muted) }
        items(files, key = { it.absolutePath }) { file ->
            Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(file.name, fontWeight = FontWeight.Bold, color = Dark)
                    Text(file.parent ?: "", color = Muted, fontSize = 12.sp)
                    Text("${file.length()} bytes", color = Muted, fontSize = 12.sp)
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
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var showHistory by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 3.dp, color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { currentUrl = "https://www.google.com"; onOpen(currentUrl) }) {
                    Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    placeholder = { Text("Search or enter address") },
                    trailingIcon = { TextButton(onClick = { showHistory = !showHistory }) { Text("History") } }
                )
                IconButton(onClick = {
                    val value = address.text.trim()
                    if (value.isNotBlank()) {
                        currentUrl = normalizeBrowserUrl(value)
                        onOpen(currentUrl)
                        onVisit(value)
                    }
                }) { Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Go") }
            }
        }

        if (showHistory) {
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(5.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("Recent history", Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                    historyItems.take(8).forEach { item ->
                        TextButton(
                            onClick = {
                                address = TextFieldValue(item.url, TextRange(item.url.length))
                                currentUrl = normalizeBrowserUrl(item.url)
                                onVisit(item.url)
                                showHistory = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(item.url, maxLines = 1, color = Dark) }
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
                    loadUrl(currentUrl)
                }
            },
            update = { view ->
                if (currentUrl != view.url && currentUrl.isNotBlank()) view.loadUrl(currentUrl)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SettingsScreen(
    context: Context,
    onStorageSettings: () -> Unit,
    historyItems: List<HistoryItem>,
    onClearHistory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Current version $CURRENT_VERSION") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                    Button(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            status = "Checking PaliaAPK HUB…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { checkWebsiteUpdate() }
                                status = result.first
                                updateUrl = result.second
                                checking = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text(if (checking) "Checking…" else "Check for Updates") }
                    if (updateUrl != null) {
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) },
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("Open Update") }
                    }
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

private suspend fun checkWebsiteUpdate(): Pair<String, String?> {
    return try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(UPDATE_MANIFEST)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "Update check failed: HTTP ${response.code}" to null
            val json = JSONObject(response.body?.string().orEmpty())
            val version = json.optString("version").removePrefix("v")
            val url = json.optString("downloadUrl", WEBSITE_URL)
            val notes = json.optString("notes")
            if (version.isBlank()) return "No PDM version published on PaliaAPK HUB." to null
            if (compareVersions(version, CURRENT_VERSION) > 0) {
                "Update available: v$version${if (notes.isNotBlank()) " — $notes" else ""}" to url
            } else {
                "You are up to date ($CURRENT_VERSION)." to null
            }
        }
    } catch (e: Throwable) {
        "Update check failed: ${e.message ?: "network error"}" to null
    }
}

private fun compareVersions(a: String, b: String): Int {
    val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(aa.size, bb.size)) {
        val x = aa.getOrElse(i) { 0 }
        val y = bb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
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

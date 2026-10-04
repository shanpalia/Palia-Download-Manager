package com.shanpalia.pdm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF16B978)
private val GreenDark = Color(0xFF07965E)
private val Blue = Color(0xFF1769D5)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Mint = Color(0xFFE9FFF6)
private val Soft = Color(0xFFF4FFFB)
private const val UPDATE_URL = "https://shanpalia.github.io/WebsitePaliaAPK_V.2/pdm-update.json"

private data class UpdateInfo(val available: Boolean, val version: String, val url: String?)

private suspend fun checkPdmUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val c = URL(UPDATE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        UpdateInfo(json.optLong("versionCode", 0L) > BuildConfig.VERSION_CODE.toLong(), json.optString("version", ""), json.optString("downloadUrl", "").ifBlank { null })
    } catch (_: Throwable) { null }
}

class PdmMainActivity : ComponentActivity() {
    private var pending: String? = null
    private var waitingStorage = false
    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val file = File(cacheDir, "selected-${System.currentTimeMillis()}.torrent")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            launchDownload(DownloadService.ACTION_TORRENT_FILE, null, file.absolutePath)
        } catch (_: Throwable) { }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            PdmApp(this, ::downloadUrl, ::openStorage, ::requestStorage) {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingStorage && PdmStorage.hasPublicAccess()) {
            waitingStorage = false
            pending?.let { pending = null; downloadUrl(it) }
        }
    }

    private fun requestStorage(url: String) { pending = url; waitingStorage = true; openStorage() }

    private fun downloadUrl(value: String) {
        val url = value.trim()
        if (url.isBlank()) return
        when {
            url.startsWith("magnet:", true) -> launchDownload(DownloadService.ACTION_TORRENT_MAGNET, url, null)
            url.startsWith("http://", true) || url.startsWith("https://", true) -> launchDownload(DownloadService.ACTION_HTTP, url, null)
        }
    }

    private fun launchDownload(action: String, url: String?, path: String?) {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
            if (url != null) putExtra(DownloadService.EXTRA_URL, url)
            if (path != null) putExtra(DownloadService.EXTRA_PATH, path)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun openStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun PdmApp(activity: ComponentActivity, onDownload: (String) -> Unit, onStorage: () -> Unit, onRequestStorage: (String) -> Unit, onTorrent: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var url by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("https://www.google.com") }
    var splash by remember { mutableStateOf(true) }
    var permission by rememberSaveable { mutableIntStateOf(0) }
    var ready by rememberSaveable { mutableStateOf(false) }
    var dialogUrl by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf(false) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) 2 else 0
        ready = permission == 0
    }

    LaunchedEffect(drawerOpen) { if (drawerOpen) drawerState.open() else drawerState.close() }
    LaunchedEffect(Unit) {
        delay(900)
        splash = false
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permission = 1
            Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess() -> permission = 2
            else -> ready = true
        }
    }
    LaunchedEffect(permission) {
        if (permission == 2) { while (!PdmStorage.hasPublicAccess()) delay(400); permission = 0; ready = true }
    }

    BackHandler(enabled = !splash) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close(); drawerOpen = false }
            dialogUrl != null -> dialogUrl = null
            screen == "Browser" -> screen = "Home"
            screen != "Home" -> screen = "Home"
            else -> exit = true
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color.White, surface = Color.White)) {
        if (splash) Splash()
        else if (!ready) PermissionScreen(permission, { if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else permission = 2 }, onStorage) { permission = 0; ready = true }
        else ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Color.White) {
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text("PDM", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Dark); Text("Palia Download Manager", color = Muted, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(18.dp))
                    DrawerItem("Home", Icons.Default.Home, screen == "Home") { screen = "Home"; drawerOpen = false }
                    DrawerItem("Browser", Icons.Default.Language, screen == "Browser") { screen = "Browser"; drawerOpen = false }
                    DrawerItem("Downloads", Icons.Default.Download, screen == "Downloads") { screen = "Downloads"; drawerOpen = false }
                    DrawerItem("History", Icons.Default.History, screen == "History") { screen = "History"; drawerOpen = false }
                    DrawerItem("Settings", Icons.Default.Settings, screen == "Settings") { screen = "Settings"; drawerOpen = false }
                    Spacer(Modifier.weight(1f))
                    Text("By PaliaAPK HUB", modifier = Modifier.padding(20.dp), color = Green, fontSize = 13.sp)
                }
            }
        ) {
            Scaffold(containerColor = Color.White, bottomBar = { PdmBottomBar(screen) { screen = it } }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        "Home" -> HomeScreen(
                            url,
                            { url = it },
                            { browserUrl = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://www.google.com/search?q=${Uri.encode(url)}"; screen = "Browser" },
                            { dialogUrl = url },
                            { target -> browserUrl = target; screen = "Browser" },
                            { screen = "Downloads" },
                            { screen = "History" },
                            onTorrent,
                            { drawerOpen = true }
                        )
                        "Downloads" -> DownloadsScreen(activity)
                        "Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }
                        "History" -> HistoryScreen(activity)
                        "Settings" -> SettingsScreen(onStorage)
                    }
                }
            }
        }

        if (dialogUrl != null) AlertDialog(
            onDismissRequest = { dialogUrl = null },
            title = { Text("Download file") },
            text = { Text(dialogUrl ?: "", maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                Button(onClick = {
                    val value = dialogUrl ?: return@Button
                    dialogUrl = null
                    if (Build.VERSION.SDK_INT >= 30 && !PdmStorage.hasPublicAccess()) onRequestStorage(value) else onDownload(value)
                    screen = "Downloads"
                }) { Text("START") }
            },
            dismissButton = { TextButton(onClick = { dialogUrl = null }) { Text("CANCEL") } }
        )

        if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text("Exit PDM?") }, text = { Text("Are you sure you want to exit?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("YES") } }, dismissButton = { TextButton(onClick = { exit = false }) { Text("NO") } })
    }
}

@Composable private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, null) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Mint, selectedIconColor = Green, selectedTextColor = Green)
    )
}

@Composable private fun PdmBottomBar(screen: String, onScreen: (String) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        BottomItem(screen == "Home", "Home", Icons.Default.Home) { onScreen("Home") }
        BottomItem(screen == "Browser", "Browser", Icons.Default.Language) { onScreen("Browser") }
        BottomItem(screen == "Downloads", "Downloads", Icons.Default.Download) { onScreen("Downloads") }
        BottomItem(screen == "History", "History", Icons.Default.History) { onScreen("History") }
        BottomItem(screen == "Settings", "Settings", Icons.Default.Settings) { onScreen("Settings") }
    }
}

@Composable private fun RowScope.BottomItem(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green, indicatorColor = Mint, unselectedIconColor = Color(0xFF87948F), unselectedTextColor = Muted))
}

@Composable private fun PdmHeader(title: String, subtitle: String? = null, onMenu: (() -> Unit)? = null, leading: ImageVector? = null, onLeading: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null && onLeading != null) {
            IconButton(onClick = onLeading) { Icon(leading, "Back", tint = Dark) }
        } else {
            AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, color = Dark, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu", tint = Dark, modifier = Modifier.size(30.dp)) }
    }
}

@Composable private fun AddressBar(value: String, onValueChange: (String) -> Unit, onGo: () -> Unit, placeholder: String = "Paste URL here...") {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(58.dp),
        singleLine = true,
        shape = RoundedCornerShape(30.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Link, null, tint = Green) },
        trailingIcon = { Button(onClick = onGo, shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 18.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Download") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() })
    )
}

@Composable private fun HomeScreen(url: String, setUrl: (String) -> Unit, go: () -> Unit, start: () -> Unit, openBrowser: (String) -> Unit, downloads: () -> Unit, history: () -> Unit, torrent: () -> Unit, openMenu: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Color.White), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AddressBar(url, setUrl, go) }
        item { PdmHeader("PDM", "Palia Download Manager", onMenu = openMenu) }
        item { Text("Quick access", Modifier.padding(horizontal = 20.dp), fontSize = 19.sp, color = Dark, fontWeight = FontWeight.Bold) }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTile("YouTube", Icons.Default.PlayCircle, Color(0xFFFFEEF1), Modifier.weight(1f)) { openBrowser("https://www.youtube.com") }
            HomeTile("Google", Icons.Default.Search, Color(0xFFF0EAFF), Modifier.weight(1f)) { openBrowser("https://www.google.com") }
            HomeTile("Facebook", Icons.Default.Public, Color(0xFFE9FFF6), Modifier.weight(1f)) { openBrowser("https://www.facebook.com") }
            HomeTile("Instagram", Icons.Default.CameraAlt, Color(0xFFFFF4DF), Modifier.weight(1f)) { openBrowser("https://www.instagram.com") }
        } }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTile("TikTok", Icons.Default.VideoLibrary, Color(0xFFEFF5FF), Modifier.weight(1f)) { openBrowser("https://www.tiktok.com") }
            HomeTile("Apps", Icons.Default.Android, Color(0xFFEAF8FF), Modifier.weight(1f)) { openBrowser("https://play.google.com") }
            HomeTile("Games", Icons.Default.SportsEsports, Color(0xFFF1F3F5), Modifier.weight(1f)) { openBrowser("https://play.google.com/store/games") }
            HomeTile("Torrent", Icons.Default.CloudDownload, Color(0xFFF6F6F6), Modifier.weight(1f)) { torrent() }
        } }
        item { Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Mint)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ready to download", fontSize = 24.sp, color = Dark, fontWeight = FontWeight.Bold)
                Text("Direct links, magnet links and .torrent files", color = Muted)
                Button(onClick = start, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Start Download") }
            }
        } }
        item { Text("Recent downloads", Modifier.padding(horizontal = 20.dp), fontSize = 19.sp, color = Dark, fontWeight = FontWeight.Bold) }
        item { RecentCard("Open Downloads", Icons.Default.Download, downloads) }
        item { RecentCard("Download History", Icons.Default.History, history) }
    }
}

@Composable private fun HomeTile(label: String, icon: ImageVector, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(vertical = 13.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(28.dp), tint = GreenDark)
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, color = Dark, maxLines = 1)
        }
    }
}

@Composable private fun RecentCard(title: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Soft), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(38.dp), tint = Green)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), color = Dark, fontSize = 16.sp)
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable private fun BrowserScreen(initial: String, onUrl: (String) -> Unit, onDownload: (String) -> Unit, onExit: () -> Unit) {
    var field by remember(initial) { mutableStateOf(initial) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onExit() }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (web?.canGoBack() == true) web?.goBack() else onExit() }) { Icon(Icons.Default.ArrowBack, "Back", tint = Dark) }
            Text("PDM Browser", modifier = Modifier.weight(1f), fontSize = 20.sp, color = Dark, fontWeight = FontWeight.Bold)
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Green) }
        }
        OutlinedTextField(value = field, onValueChange = { field = it; onUrl(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(56.dp), singleLine = true, shape = RoundedCornerShape(28.dp), placeholder = { Text("Search or type URL") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Green) }, trailingIcon = { IconButton(onClick = { loadBrowser(field, web) }) { Icon(Icons.Default.ArrowForward, "Go", tint = Green) } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { loadBrowser(field, web) }))
        AndroidView(factory = { ctx -> WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, pageUrl: String) { field = pageUrl; onUrl(pageUrl) }
            }
            setDownloadListener { u, _, _, _, _ -> if (!u.isNullOrBlank()) onDownload(u) }
            loadUrl(initial)
            web = this
        } }, modifier = Modifier.fillMaxSize())
    }
}

private fun loadBrowser(value: String, web: WebView?) {
    val v = value.trim()
    if (v.isBlank()) return
    web?.loadUrl(if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://www.google.com/search?q=${Uri.encode(v)}")
}

@Composable private fun DownloadsScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(700) } }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        PdmHeader("Downloads", "Manage your downloaded files")
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(true, "All ${files.size}"); FilterChip(false, "Downloading"); FilterChip(false, "Completed") }
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(files, key = { it.absolutePath }) { file -> DownloadFileCard(file) }; if (files.isEmpty()) item { EmptyState("No downloads yet", "Start a download from the Home or Browser screen.") } }
    }
}

@Composable private fun FilterChip(selected: Boolean, text: String) { Surface(shape = RoundedCornerShape(22.dp), color = if (selected) Green else Soft) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selected) Color.White else Dark, fontSize = 12.sp) } }

@Composable private fun DownloadFileCard(file: File) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { FileIcon(file.name); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dark, fontSize = 15.sp); Text("${file.length()} bytes • Completed", color = Muted, fontSize = 12.sp) }; Icon(Icons.Default.CheckCircle, null, tint = Green) } } }

@Composable private fun HistoryScreen(context: Context) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { files = PdmStorage.allFiles(context); delay(1000) } }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        PdmHeader("Download History", "Your downloaded files")
        if (files.isEmpty()) EmptyState("No history yet", "Completed downloads will appear here.") else LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(files, key = { "history-${it.absolutePath}" }) { file -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Soft)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { FileIcon(file.name); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dark); Text("Completed • ${file.length()} bytes", color = Muted, fontSize = 12.sp) }; Icon(Icons.Default.MoreVert, null, tint = Muted) } } } }
    }
}

@Composable private fun FileIcon(name: String) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val icon = when (ext) { "mp3", "wav", "m4a" -> Icons.Default.MusicNote; "mp4", "mkv", "webm", "avi" -> Icons.Default.PlayCircle; "apk" -> Icons.Default.Android; "zip", "rar", "7z" -> Icons.Default.FolderZip; "torrent" -> Icons.Default.CloudDownload; "jpg", "jpeg", "png", "webp" -> Icons.Default.Image; "pdf" -> Icons.Default.PictureAsPdf; else -> Icons.Default.InsertDriveFile }
    Surface(Modifier.size(48.dp), shape = RoundedCornerShape(13.dp), color = Mint) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = GreenDark) } }
}

@Composable private fun EmptyState(title: String, text: String) { Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { Surface(Modifier.size(82.dp), shape = RoundedCornerShape(28.dp), color = Mint) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, Modifier.size(40.dp), tint = Green) } }; Spacer(Modifier.height(16.dp)); Text(title, fontSize = 21.sp, color = Dark, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(text, color = Muted, fontSize = 14.sp) } }

@Composable private fun SettingsScreen(onStorage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Not checked") }
    var version by rememberSaveable { mutableStateOf("") }
    var updateUrl by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().background(Color.White), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PdmHeader("Settings", "PDM preferences") }
        item { Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Mint)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, Modifier.size(68.dp)); Spacer(Modifier.width(14.dp)); Column { Text("PDM", fontSize = 26.sp, color = Dark, fontWeight = FontWeight.Bold); Text("PaliaAPK HUB", fontSize = 16.sp, color = Green); Text("Download Manager", fontSize = 13.sp, color = Muted) } } } }
        item { SettingSection("Download Options") { SettingRow(Icons.Default.Folder, "Storage access", "Choose where PDM can save files") { onStorage() }; SettingRow(Icons.Default.Download, "Auto resume", "Resume supported interrupted downloads", null); SettingRow(Icons.Default.Speed, "Download speed", "Unlimited", null) } }
        item { SettingSection("Browser & Clipboard") { SettingRow(Icons.Default.Language, "Built-in Browser", "Open links inside PDM", null); SettingRow(Icons.Default.Link, "Auto detect copied link", "Show download action for copied links", null) } }
        item { SettingSection("Notifications") { SettingRow(Icons.Default.Notifications, "Download complete", "Notify when a download finishes", null) } }
        item { Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Soft)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SystemUpdate, null, tint = Green, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(10.dp)); Text("App updates", fontSize = 20.sp, color = Dark, fontWeight = FontWeight.Bold) }; Text(if (status == "Not checked") "Check the official Palia website for the latest release." else status, color = Muted); if (version.isNotBlank()) Text("Website version: $version", color = Dark, fontSize = 13.sp); Button(enabled = !checking, onClick = { scope.launch { checking = true; status = "Checking website…"; val r = checkPdmUpdate(); if (r == null) { status = "Could not check for updates"; version = ""; updateUrl = "" } else if (r.available) { status = "Update available • v${r.version}"; version = r.version; updateUrl = r.url ?: "" } else { status = "Up to date • v${BuildConfig.VERSION_NAME}"; version = r.version; updateUrl = "" }; checking = false } }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (checking) "Checking…" else "Check for updates") }; if (updateUrl.isNotBlank()) Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Open update") } } } }
        item { Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFA))) { Column(Modifier.padding(18.dp)) { Text("PDM", fontSize = 18.sp, color = Dark); Text("By PaliaAPK HUB", color = Green, fontSize = 16.sp); Text("Developer by shanpalia", color = Muted, fontSize = 14.sp); Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp) } } }
    }
}

@Composable private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) { Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) { Text(title, Modifier.padding(start = 4.dp, bottom = 5.dp), fontSize = 18.sp, color = Dark, fontWeight = FontWeight.Bold); Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Soft)) { Column(content = content) } } }

@Composable private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) { Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = Mint) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = GreenDark) } }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, color = Dark, fontSize = 15.sp); Text(subtitle, color = Muted, fontSize = 12.sp) }; if (onClick != null) Icon(Icons.Default.ChevronRight, null, tint = Muted) } }

@Composable private fun Splash() { Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { AndroidView(factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_pdm_logo) } }, Modifier.size(116.dp)); Text("PDM", fontSize = 30.sp, color = Dark, fontWeight = FontWeight.Bold); Text("Palia Download Manager", color = Green, fontSize = 17.sp); Text("By PaliaAPK HUB", color = Muted); Text("Developer by shanpalia", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.width(160.dp), color = Green) } } }

@Composable private fun PermissionScreen(step: Int, notification: () -> Unit, storage: () -> Unit, skip: () -> Unit) { val n = step == 1; Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(Modifier.padding(24.dp), colors = CardDefaults.cardColors(containerColor = Mint), shape = RoundedCornerShape(28.dp)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(if (n) Icons.Default.Notifications else Icons.Default.Folder, null, Modifier.size(56.dp), tint = Green); Text(if (n) "Allow notifications" else "Allow storage access", fontSize = 20.sp, color = Dark); Text(if (n) "PDM can notify you when downloads finish." else "Storage access is required to save downloaded files.", color = Muted); Button(onClick = if (n) notification else storage, shape = RoundedCornerShape(24.dp)) { Text("Continue") }; TextButton(onClick = skip) { Text("Skip") } } } } }

package com.shanpalia.pdm

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Green = Color(0xFF16B978)
private val Blue = Color(0xFF0B62D6)
private val Dark = Color(0xFF12231E)
private val Muted = Color(0xFF6F7D77)
private val Mint = Color(0xFFE9FFF6)

private data class HistoryItem(val url: String, val time: Long)
private class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("pdm_history", Context.MODE_PRIVATE)
    fun all(): List<HistoryItem> = (prefs.getString("items", "") ?: "").split("\n").mapNotNull { line -> val p=line.split("\t",limit=2); if(p.size==2&&p[0].isNotBlank()) HistoryItem(p[0],p[1].toLongOrNull()?:0L) else null }
    fun add(url:String){val c=url.trim();if(c.isBlank())return;val l=(listOf(HistoryItem(c,System.currentTimeMillis()))+all().filterNot{it.url==c}).take(100);prefs.edit().putString("items",l.joinToString("\n"){ "${it.url}\t${it.time}" }).apply()}
    fun remove(url:String){val l=all().filterNot{it.url==url};prefs.edit().putString("items",l.joinToString("\n"){ "${it.url}\t${it.time}" }).apply()}
    fun clear()=prefs.edit().remove("items").apply()
}

class MainActivity : ComponentActivity() {
    private var pendingDownload:String?=null
    private var waitingForStorage=false
    private val torrentPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)try{val t=File(cacheDir,"selected-${System.currentTimeMillis()}.torrent");contentResolver.openInputStream(uri)?.use{input->t.outputStream().use{out->input.copyTo(out)}};startDownloadService(DownloadService.ACTION_TORRENT_FILE,null,t.absolutePath)}catch(_:Throwable){}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{PdmApp(this,extractIncoming(intent),::startUrlDownload,{torrentPicker.launch(arrayOf("application/x-bittorrent","application/octet-stream","*/*"))},::openStorageSettings,::requestStorageAndDownload)}}
    override fun onResume(){super.onResume();if(waitingForStorage&&PdmStorage.hasPublicAccess()){waitingForStorage=false;pendingDownload?.let{pendingDownload=null;startUrlDownload(it)}}}
    private fun requestStorageAndDownload(url:String){pendingDownload=url;waitingForStorage=true;openStorageSettings()}
    private fun extractIncoming(i:Intent?):String?=if(i?.action==Intent.ACTION_SEND)i.getStringExtra(Intent.EXTRA_TEXT) else i?.data?.takeIf{it.scheme in listOf("http","https","magnet")}?.toString()
    private fun startUrlDownload(v:String){val c=v.trim();if(c.isBlank())return;when{c.startsWith("magnet:",true)->startDownloadService(DownloadService.ACTION_TORRENT_MAGNET,c,null);c.substringBefore('?').substringBefore('#').endsWith(".torrent",true)->startDownloadService(DownloadService.ACTION_TORRENT_URL,c,null);c.startsWith("http://",true)||c.startsWith("https://",true)->startDownloadService(DownloadService.ACTION_HTTP,c,null)}}
    private fun startDownloadService(action:String,url:String?,path:String?){val i=Intent(this,DownloadService::class.java).apply{this.action=action;if(url!=null)putExtra(DownloadService.EXTRA_URL,url);if(path!=null)putExtra(DownloadService.EXTRA_PATH,path)};if(Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i)}
    private fun openStorageSettings(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R)try{startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")))}catch(_:Throwable){startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))}}
}

@Composable private fun PdmApp(activity:ComponentActivity,incomingUrl:String?,onDownload:(String)->Unit,onPickTorrent:()->Unit,onStorageSettings:()->Unit,requestStorage:(String)->Unit){
    val context=activity;val history=remember(context){HistoryStore(context)};var historyItems by remember{mutableStateOf(history.all())};var screen by rememberSaveable{mutableStateOf("Home")};var address by rememberSaveable{mutableStateOf(incomingUrl.orEmpty())};var browserUrl by rememberSaveable{mutableStateOf(incomingUrl?.takeIf{it.startsWith("http",true)}?:"https://www.google.com")};var splash by remember{mutableStateOf(true)};var permissionGate by remember{mutableStateOf(false)};var storagePrompt by remember{mutableStateOf<String?>(null)};var exitDialog by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){delay(1800);splash=false}
    LaunchedEffect(splash){if(!splash){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),501);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R&&!PdmStorage.hasPublicAccess()){permissionGate=true;onStorageSettings()}}}
    LaunchedEffect(permissionGate){if(permissionGate&&PdmStorage.hasPublicAccess())permissionGate=false}
    BackHandler(enabled=!splash){if(!permissionGate){if(screen=="Home")exitDialog=true else screen="Home"}}
    fun saveHistory(v:String){history.add(v);historyItems=history.all()}
    fun requestDownload(v:String){val c=v.trim();if(c.isBlank())return;saveHistory(c);screen="Downloads";if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R&&!PdmStorage.hasPublicAccess())storagePrompt=c else onDownload(c)}
    fun openAddress(v:String){val c=v.trim();if(c.isBlank())return;if(c.startsWith("magnet:",true)||isDownloadLink(c))requestDownload(c)else if(c.startsWith("http://",true)||c.startsWith("https://",true)){saveHistory(c);browserUrl=c;screen="Browser"}else{saveHistory(c);browserUrl="https://www.google.com/search?q=${Uri.encode(c)}";screen="Browser"}}
    MaterialTheme(colorScheme=lightColorScheme(primary=Green,background=Color.White,surface=Color.White)){if(splash){SplashScreen();return@MaterialTheme};if(permissionGate){PermissionScreen(onStorageSettings,{permissionGate=false});return@MaterialTheme};Scaffold(containerColor=Color.White,bottomBar={NavigationBar(containerColor=Color(0xFFF4FFFB),tonalElevation=0.dp){NavItem("Home",Icons.Default.Home,screen=="Home"){screen="Home"};NavItem("Downloads",Icons.Default.Download,screen=="Downloads"){screen="Downloads"};NavItem("Browser",Icons.Default.Web,screen=="Browser"){screen="Browser"};NavItem("Settings",Icons.Default.Settings,screen=="Settings"){screen="Settings"}}}){padding->Box(Modifier.fillMaxSize().padding(padding)){when(screen){"Home"->HomeScreen(context,address,{address=it},{openAddress(address)},{screen="Browser"},{screen="Downloads"},onPickTorrent,{requestDownload(address)},historyItems,{v->address=v;openAddress(v)},{v->history.remove(v);historyItems=history.all()},{history.clear();historyItems=emptyList()});"Downloads"->DownloadsScreen(context);"Browser"->BrowserScreen(context,browserUrl,{saveHistory(it)},{requestDownload(it)},{browserUrl=it});else->SettingsScreen(historyItems,{history.clear();historyItems=emptyList()},onStorageSettings)}}};storagePrompt?.let{link->AlertDialog(onDismissRequest={storagePrompt=null},title={Text("Storage access")},text={Text("Allow storage access so PDM can save files in Download/PDM and its category folders.")},confirmButton={Button(onClick={storagePrompt=null;requestStorage(link)}){Text("Allow")}},dismissButton={TextButton(onClick={storagePrompt=null}){Text("Cancel")}})};if(exitDialog)AlertDialog(onDismissRequest={exitDialog=false},title={Text("Exit Palia Download Manager?")},text={Text("Are you sure you want to exit?")},confirmButton={TextButton(onClick={activity.finish()}){Text("YES")}},dismissButton={TextButton(onClick={exitDialog=false}){Text("NO")}})}}
}

@Composable private fun PermissionScreen(onOpen:()->Unit,onContinue:()->Unit){Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Mint)){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){Icon(Icons.Default.Folder,null,Modifier.size(52.dp),tint=Green);Text("Allow storage access",fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Text("PDM needs storage access before Home so downloads can be saved directly in Download/PDM.",color=Muted);Button(onClick=onOpen){Text("Allow storage")};TextButton(onClick=onContinue){Text("Continue")}}}}}
@Composable private fun RowScope.NavItem(label:String,icon:ImageVector,selected:Boolean,onClick:()->Unit){NavigationBarItem(selected=selected,onClick=onClick,icon={Icon(icon,null)},label={Text(label)})}
@Composable private fun SplashScreen(){Box(Modifier.fillMaxSize().background(Color.White),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Image(painterResource(R.drawable.ic_pdm_logo),"Palia Download Manager",Modifier.size(210.dp));Text("Palia Download Manager",fontSize=24.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Text("FAST • SMART • SECURE",color=Blue,fontSize=13.sp,fontWeight=FontWeight.Bold);LinearProgressIndicator(Modifier.width(250.dp).height(6.dp).clip(RoundedCornerShape(50)),color=Green);Text("Developer By Shanpalia",color=Muted,fontSize=13.sp)}}}

@Composable private fun HomeScreen(context:Context,url:String,onUrl:(String)->Unit,onGo:()->Unit,onBrowser:()->Unit,onDownloads:()->Unit,onTorrent:()->Unit,onAddDownload:()->Unit,historyItems:List<HistoryItem>,onHistoryClick:(String)->Unit,onHistoryDelete:(String)->Unit,onClearHistory:()->Unit){var field by remember(url){mutableStateOf(TextFieldValue(url,TextRange(url.length)))};LaunchedEffect(url){if(field.text!=url)field=TextFieldValue(url,TextRange(url.length))};LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(top=8.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{OutlinedTextField(value=field,onValueChange={field=it;onUrl(it.text)},modifier=Modifier.fillMaxWidth().height(56.dp),singleLine=true,shape=RoundedCornerShape(15.dp),placeholder={Text("Paste here")},leadingIcon={Icon(Icons.Default.Language,null,tint=Dark)},trailingIcon={Row(verticalAlignment=Alignment.CenterVertically){if(field.text.isNotBlank())IconButton(onClick={field=TextFieldValue("");onUrl("")}){Icon(Icons.Default.Clear,"Clear")};TextButton(onClick={val v=readClipboard(context);field=TextFieldValue(v,TextRange(v.length));onUrl(v)}){Text("PASTE",color=Green,fontWeight=FontWeight.Bold)};FilledIconButton(onClick=onGo,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Blue)){Icon(Icons.Default.ArrowForward,"Go")}}})};item{Row(verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.ic_pdm_logo),"PDM",Modifier.size(64.dp));Spacer(Modifier.width(10.dp));Column{Text("Palia Download Manager",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Text("Fast • Smart • Secure",color=Muted)}}};item{Card(colors=CardDefaults.cardColors(containerColor=Mint),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("Ready to download?",fontSize=23.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Text("HTTP/HTTPS files, .torrent and magnet links",color=Muted);Button(onClick=onAddDownload,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("Start Download")};OutlinedButton(onClick=onTorrent,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.FolderOpen,null);Spacer(Modifier.width(8.dp));Text("Open .torrent file")};OutlinedButton(onClick=onDownloads,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.List,null);Spacer(Modifier.width(8.dp));Text("View Downloads")}}}};item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()){OutlinedButton(onClick=onBrowser,modifier=Modifier.weight(1f)){Text("Browser")};OutlinedButton(onClick=onDownloads,modifier=Modifier.weight(1f)){Text("Downloads")}}};item{Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Text("Recent history",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Dark,modifier=Modifier.weight(1f));if(historyItems.isNotEmpty())TextButton(onClick=onClearHistory){Text("Clear")}}};if(historyItems.isEmpty())item{Text("Your recent links will appear here.",color=Muted)}else items(historyItems.take(10),key={it.url}){item->Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF7FAF9)),shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){Text(item.url,modifier=Modifier.weight(1f),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=Dark);IconButton(onClick={onHistoryClick(item.url)}){Icon(Icons.Default.OpenInBrowser,"Open")};IconButton(onClick={onHistoryDelete(item.url)}){Icon(Icons.Default.DeleteOutline,"Delete")}}}}}}

@Composable private fun DownloadsScreen(context:Context){var files by remember{mutableStateOf(PdmStorage.allFiles(context))};var confirm by remember{mutableStateOf<File?>(null)};LaunchedEffect(Unit){while(true){files=PdmStorage.allFiles(context);delay(1000)}};Column(Modifier.fillMaxSize().padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Column(Modifier.weight(1f)){Text("Downloads",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Text("${files.size} file(s) • Download/PDM",color=Muted)};IconButton(onClick={files=PdmStorage.allFiles(context)}){Icon(Icons.Default.Refresh,"Refresh")}};if(files.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.DownloadDone,null,Modifier.size(60.dp),tint=Green);Text("No downloads yet",fontSize=20.sp,fontWeight=FontWeight.Bold)}}else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=90.dp)){items(files,key={it.absolutePath}){file->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(fileIcon(file),null,tint=Blue,modifier=Modifier.size(34.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(file.name,maxLines=2,fontWeight=FontWeight.SemiBold,color=Dark);Text("${formatSize(file.length())} • ${file.parentFile?.name?:"PDM"}",fontSize=12.sp,color=Muted)};IconButton(onClick={confirm=file}){Icon(Icons.Default.Delete,"Delete download",tint=Color(0xFFD32F2F))}}}}}}};confirm?.let{file->AlertDialog(onDismissRequest={confirm=null},title={Text("Delete download?")},text={Text("Delete ${file.name} permanently?")},confirmButton={TextButton(onClick={try{file.deleteRecursively()}catch(_:Throwable){};confirm=null;files=PdmStorage.allFiles(context)}){Text("DELETE",color=Color(0xFFD32F2F))}},dismissButton={TextButton(onClick={confirm=null}){Text("CANCEL")}})}}}
private fun fileIcon(file:File):ImageVector=when(PdmStorage.categoryFor(file.name)){"Images"->Icons.Default.Image;"Videos"->Icons.Default.Movie;"Music"->Icons.Default.MusicNote;"APK"->Icons.Default.Android;"ZIP"->Icons.Default.Archive;"Documents"->Icons.Default.Description;"Torrents"->Icons.Default.CloudDownload;else->Icons.Default.InsertDriveFile}

@Composable private fun BrowserScreen(context:Context,initialUrl:String,onHistory:(String)->Unit,onDownload:(String)->Unit,onUrlChanged:(String)->Unit){var field by remember(initialUrl){mutableStateOf(TextFieldValue(initialUrl,TextRange(initialUrl.length)))};var webView by remember{mutableStateOf<WebView?>(null)};Column(Modifier.fillMaxSize()){OutlinedTextField(value=field,onValueChange={field=it;onUrlChanged(it.text)},modifier=Modifier.fillMaxWidth().padding(8.dp),singleLine=true,placeholder={Text("Paste here")},leadingIcon={Icon(Icons.Default.Language,null)},trailingIcon={IconButton(onClick={val v=readClipboard(context);field=TextFieldValue(v,TextRange(v.length));if(v.isNotBlank())webView?.loadUrl(normalizeUrl(v))}){Icon(Icons.Default.ContentPaste,"Paste")}});Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){IconButton(onClick={webView?.goBack()}){Icon(Icons.Default.ArrowBack,"Back")};IconButton(onClick={webView?.goForward()}){Icon(Icons.Default.ArrowForward,"Forward")};IconButton(onClick={webView?.reload()}){Icon(Icons.Default.Refresh,"Refresh")};Button(onClick={val v=field.text.trim();if(v.isNotBlank()){onHistory(v);if(isDownloadLink(v))onDownload(v)else webView?.loadUrl(normalizeUrl(v))}}){Text("GO")}};AndroidView(factory={WebView(it).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=true;webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{val u=request.url.toString();if(isDownloadLink(u)){onDownload(u);return true};onHistory(u);field=TextFieldValue(u,TextRange(u.length));onUrlChanged(u);return false};override fun onPageFinished(view:WebView,url:String){field=TextFieldValue(url,TextRange(url.length));onUrlChanged(url)}};loadUrl(normalizeUrl(initialUrl));webView=this}},modifier=Modifier.fillMaxSize())}}

@Composable private fun SettingsScreen(history:List<HistoryItem>,clearHistory:()->Unit,storageSettings:()->Unit){Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Settings",fontSize=28.sp,fontWeight=FontWeight.ExtraBold,color=Dark);Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Storage",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("Files are saved under Download/PDM with category folders.",color=Muted);Button(onClick=storageSettings){Text("Storage permission")}}};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("History",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("${history.size} saved link(s)",color=Muted);OutlinedButton(onClick=clearHistory){Text("Clear history")}}}}}

private fun readClipboard(context:Context):String{val manager=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;return manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}
private fun normalizeUrl(value:String):String{val v=value.trim();return if(v.startsWith("http://",true)||v.startsWith("https://",true))v else "https://www.google.com/search?q=${Uri.encode(v)}"}
private fun isDownloadLink(value:String):Boolean{val clean=value.substringBefore('#');val path=clean.substringBefore('?').lowercase();if(clean.startsWith("magnet:",true))return true;return listOf(".zip",".apk",".torrent",".rar",".7z",".pdf",".mp3",".mp4",".mkv",".jpg",".jpeg",".png",".webp",".doc",".docx",".xls",".xlsx",".ppt",".pptx").any{path.endsWith(it)}}
private fun formatSize(bytes:Long):String{if(bytes<1024)return "$bytes B";if(bytes<1024*1024)return "%.1f KB".format(bytes/1024.0);if(bytes<1024L*1024L*1024L)return "%.1f MB".format(bytes/1024.0/1024.0);return "%.2f GB".format(bytes/1024.0/1024.0/1024.0)}

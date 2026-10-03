from pathlib import Path

path = Path("app/src/main/java/com/shanpalia/pdm/MainActivity.kt")
text = path.read_text(encoding="utf-8")

old = '''@Composable
private fun SettingsScreen(historyItems: List<HistoryItem>, onClear: () -> Unit, onStorage: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Settings", onMenu)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("Downloads are saved in Download/PDM with automatic categories.", color = Muted)
            Button(onClick = onStorage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Storage access") }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear recent tabs") }
            Text("Recent tabs: ${historyItems.size}", color = Muted, fontSize = 13.sp)
        }
    }
}'''

new = '''@Composable
private fun SettingsScreen(historyItems: List<HistoryItem>, onClear: () -> Unit, onStorage: () -> Unit, onMenu: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize()) {
        DrawerTopBar("Settings", onMenu)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Palia Download Manager", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Dark)
            Text("Downloads are saved in Download/PDM with automatic categories.", color = Muted)

            Button(onClick = onStorage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null)
                Spacer(Modifier.width(8.dp))
                Text("Storage access")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Mint),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, tint = Green, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("App updates", fontWeight = FontWeight.Bold, color = Dark)
                            Text("Check the latest Palia Download Manager release.", color = Muted, fontSize = 13.sp)
                        }
                    }
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shanpalia/Palia-Download-Manager/releases"))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Check for updates")
                    }
                }
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DeleteSweep, null)
                Spacer(Modifier.width(8.dp))
                Text("Clear recent tabs")
            }
            Text("Recent tabs: ${historyItems.size}", color = Muted, fontSize = 13.sp)
        }
    }
}'''

if old in text:
    text = text.replace(old, new, 1)

# Browser-style back: Android back should traverse WebView history before leaving Browser.
old_call = '''"Browser" -> BrowserScreen(browserUrl, { address = it; browserUrl = it; saveHistory(it) }, { showDownload(it) }, { browserUrl = it; address = it }, { scope.launch { drawerState.open() } })'''
new_call = '''"Browser" -> BrowserScreen(browserUrl, { address = it; browserUrl = it; saveHistory(it) }, { showDownload(it) }, { browserUrl = it; address = it }, { scope.launch { drawerState.open() } }, { screen = "Home" })'''
if old_call in text and new_call not in text:
    text = text.replace(old_call, new_call, 1)

old_sig = '''    onUrlChange: (String) -> Unit,
    onMenu: () -> Unit
) {'''
new_sig = '''    onUrlChange: (String) -> Unit,
    onMenu: () -> Unit,
    onExit: () -> Unit
) {'''
if old_sig in text and 'onExit: () -> Unit' not in text:
    text = text.replace(old_sig, new_sig, 1)

anchor = '''    var field by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(Modifier.fillMaxSize()) {'''
replacement = '''    var field by remember(initialUrl) { mutableStateOf(TextFieldValue(initialUrl, TextRange(initialUrl.length))) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Browser-style back: keep navigation inside the WebView while history exists.
    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onExit()
    }

    Column(Modifier.fillMaxSize()) {'''
if anchor in text and 'Browser-style back: keep navigation inside the WebView' not in text:
    text = text.replace(anchor, replacement, 1)

path.write_text(text, encoding="utf-8")
print("Settings update and browser back fixes applied")

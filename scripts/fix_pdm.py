from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"

NEW_BROWSER = r'''
@Composable
private fun BrowserScreen(
    initialUrl: String,
    onUrlChange: (String) -> Unit,
    onDownload: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var address by rememberSaveable(initialUrl) { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val google = "https://www.google.com"

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Dark) }
            Text("PDM Browser", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Dark, modifier = Modifier.weight(1f))
            IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Green, modifier = Modifier.size(30.dp)) }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(58.dp),
            singleLine = true,
            shape = RoundedCornerShape(30.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Green) },
            trailingIcon = {
                IconButton(onClick = {
                    val raw = address.trim()
                    val target = if (raw.isBlank()) google else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://www.google.com/search?q=${Uri.encode(raw)}"
                    address = target
                    webViewRef?.loadUrl(target)
                }) { Icon(Icons.Default.ArrowForward, "Go", tint = Green, modifier = Modifier.size(30.dp)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val raw = address.trim()
                val target = if (raw.isBlank()) google else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://www.google.com/search?q=${Uri.encode(raw)}"
                address = target
                webViewRef?.loadUrl(target)
            })
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                                address = url
                                onUrlChange(url)
                                PdmWebHistory.record(context, view.title, url)
                            }
                        }
                    }
                    webViewRef = this
                    val first = if (initialUrl.startsWith("http://", true) || initialUrl.startsWith("https://", true)) initialUrl else google
                    loadUrl(first)
                }
            },
            update = { webViewRef = it }
        )
    }
}
'''


def find_function(text, name):
    marker = f"private fun {name}("
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{name} not found")
    ann = text.rfind("@Composable", 0, start)
    if ann < 0:
        ann = start
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"{name} body not found")

    depth = 0
    i = brace
    in_line = False
    in_block = False
    in_string = False
    in_triple = False
    escape = False

    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if in_line:
            if c == "\n": in_line = False
        elif in_block:
            if c == "*" and n == "/": in_block = False; i += 1
        elif in_triple:
            if text.startswith('"""', i): in_triple = False; i += 2
        elif in_string:
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == '"': in_string = False
        else:
            if c == "/" and n == "/": in_line = True; i += 1
            elif c == "/" and n == "*": in_block = True; i += 1
            elif text.startswith('"""', i): in_triple = True; i += 2
            elif c == '"': in_string = True
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0: return ann, i + 1
        i += 1
    raise SystemExit(f"Unbalanced braces in {name}")

text = KOTLIN.read_text(encoding="utf-8")
text = text.replace('"History" -> HistoryScreen(activity)', '"History" -> RecentHistoryScreen(activity)')
start, end = find_function(text, "BrowserScreen")
text = text[:start] + NEW_BROWSER.strip() + text[end:]
KOTLIN.write_text(text, encoding="utf-8")
print("PDM source patched: Google browser, right arrow, and persistent Recent web history")

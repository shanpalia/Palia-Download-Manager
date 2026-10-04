from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# Keep all navigation callbacks in typed local variables. This removes every
# trailing-lambda ambiguity from the when(screen) call sites and also gives the
# Kotlin compiler explicit String parameter types.
anchor = '                Box(Modifier.fillMaxSize().padding(padding)) {\n                    when (screen) {'
replacement = '''                Box(Modifier.fillMaxSize().padding(padding)) {
                    val browserOnUrlChange: (String) -> Unit = { value: String -> browserUrl = value }
                    val browserOnDownload: (String) -> Unit = { value: String -> dialogUrl = value }
                    val browserOnBack: () -> Unit = { screen = "Home" }
                    val historyOnOpenUrl: (String) -> Unit = { target: String -> browserUrl = target; screen = "Browser" }
                    when (screen) {'''
text = text.replace(anchor, replacement, 1)

# Replace the two problematic call sites with ordinary function arguments only.
text, browser_count = re.subn(
    r'(?m)^(\s*)"Browser"\s*->.*$',
    r'\1"Browser" -> BrowserScreen(browserUrl, browserOnUrlChange, browserOnDownload, browserOnBack)',
    text,
)
text, history_count = re.subn(
    r'(?m)^(\s*)"History"\s*->.*$',
    r'\1"History" -> HistoryScreen(activity, historyOnOpenUrl)',
    text,
)

# Rename PDM's two-argument download filter so it cannot collide with Material3
# FilterChip, whose signature has named callbacks and a label lambda.
text = text.replace(
    '@Composable private fun FilterChip(selected: Boolean, text: String)',
    '@Composable private fun PdmFilterChip(selected: Boolean, text: String)'
)
text = text.replace('FilterChip(true, "All ${files.size}")', 'PdmFilterChip(true, "All ${files.size}")')
text = text.replace('FilterChip(false, "Downloading")', 'PdmFilterChip(false, "Downloading")')
text = text.replace('FilterChip(false, "Completed")', 'PdmFilterChip(false, "Completed")')

KOTLIN.write_text(text, encoding="utf-8")

# Verify the exact compiler-safe forms that must reach Kotlin.
final_text = KOTLIN.read_text(encoding="utf-8")
expected_browser = '"Browser" -> BrowserScreen(browserUrl, browserOnUrlChange, browserOnDownload, browserOnBack)'
expected_history = '"History" -> HistoryScreen(activity, historyOnOpenUrl)'
expected_callback = 'val historyOnOpenUrl: (String) -> Unit = { target: String -> browserUrl = target; screen = "Browser" }'

if expected_browser not in final_text:
    raise SystemExit("ERROR: deterministic BrowserScreen call was not produced")
if expected_history not in final_text:
    raise SystemExit("ERROR: deterministic HistoryScreen call was not produced")
if expected_callback not in final_text:
    raise SystemExit("ERROR: typed History callback was not produced")

# The navigation call sites must contain no lambda braces at all.
for line in final_text.splitlines():
    if '"Browser" -> BrowserScreen(' in line or '"History" -> HistoryScreen(' in line:
        if '{' in line or '}' in line:
            raise SystemExit("ERROR: navigation call still contains a lambda expression")

print(f"PDM deterministic Kotlin navigation fix applied (Browser lines: {browser_count}, History lines: {history_count})")

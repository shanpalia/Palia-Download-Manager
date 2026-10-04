from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# The source patchers rebuild PdmMainActivity.kt during every Gradle build.
# Do not try to match one exact previous spelling: normalize the complete
# navigation lines regardless of indentation or earlier patch variants.
text, browser_count = re.subn(
    r'(?m)^(\s*)"Browser"\s*->.*$',
    r'\1"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })',
    text,
)

text, history_count = re.subn(
    r'(?m)^(\s*)"History"\s*->.*$',
    r'\1"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })',
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

# Verify the exact Kotlin forms that must reach the compiler.
final_text = KOTLIN.read_text(encoding="utf-8")
expected_browser = '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })'
expected_history = '"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })'

if expected_browser not in final_text:
    raise SystemExit("ERROR: deterministic BrowserScreen call was not produced")
if expected_history not in final_text:
    raise SystemExit("ERROR: deterministic HistoryScreen call was not produced")
if re.search(r'(?m)^\s*"Browser"\s*->.*\}\s*\{', final_text):
    raise SystemExit("ERROR: BrowserScreen still has a trailing lambda outside parentheses")
if re.search(r'(?m)^\s*"History"\s*->.*\}\s*\{', final_text):
    raise SystemExit("ERROR: HistoryScreen still has a trailing lambda outside parentheses")

print(f"PDM final Kotlin compile-call fix applied (Browser lines: {browser_count}, History lines: {history_count})")

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# The source patchers can reintroduce the old Kotlin trailing-lambda form.
# Normalize the entire Browser destination line deterministically.
text, browser_count = re.subn(
    r'(?m)^\s*"Browser"\s*->\s*BrowserScreen\(browserUrl,\s*\{\s*browserUrl\s*=\s*it\s*\},\s*\{\s*dialogUrl\s*=\s*it\s*\}\)\s*\{\s*screen\s*=\s*"Home"\s*\}\s*$',
    '                        "Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })',
    text,
)

# Also handle the exact legacy spelling if a patcher changes indentation.
text = text.replace(
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }',
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })'
)

# History must use the explicit callback parameter; never leave a second
# trailing lambda after HistoryScreen(activity).
text = re.sub(
    r'(?m)^\s*"History"\s*->\s*HistoryScreen\(activity\)\s*\{\s*target\s*->\s*browserUrl\s*=\s*target\s*;\s*screen\s*=\s*"Browser"\s*\}\s*$',
    '                        "History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })',
    text,
)
text = text.replace(
    '"History" -> HistoryScreen(activity)',
    '"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })'
)

# Rename PDM's two-argument download filter so it cannot collide with Material3 FilterChip.
text = text.replace(
    '@Composable private fun FilterChip(selected: Boolean, text: String)',
    '@Composable private fun PdmFilterChip(selected: Boolean, text: String)'
)
text = text.replace('FilterChip(true, "All ${files.size}")', 'PdmFilterChip(true, "All ${files.size}")')
text = text.replace('FilterChip(false, "Downloading")', 'PdmFilterChip(false, "Downloading")')
text = text.replace('FilterChip(false, "Completed")', 'PdmFilterChip(false, "Completed")')

KOTLIN.write_text(text, encoding="utf-8")

# Fail the patch step immediately if the compiler-breaking form survives.
final_text = KOTLIN.read_text(encoding="utf-8")
if 'BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }' in final_text:
    raise SystemExit("ERROR: legacy BrowserScreen trailing-lambda syntax survived patch")
if re.search(r'(?m)^\s*"History"\s*->\s*HistoryScreen\(activity\)\s*\{', final_text):
    raise SystemExit("ERROR: legacy HistoryScreen trailing-lambda syntax survived patch")

print(f"PDM final Kotlin compile-call fix applied (Browser replacements: {browser_count})")

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# Fix Kotlin trailing-lambda syntax robustly. Kotlin allows only one lambda
# outside the parenthesized argument list. BrowserScreen has multiple callbacks,
# so ALL callbacks must remain inside the parentheses.
text = re.sub(
    r'"Browser"\s*->\s*BrowserScreen\(browserUrl,\s*\{ browserUrl = it \},\s*\{ dialogUrl = it \}\)\s*\{ screen = "Home" \}',
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })',
    text,
)

# Also normalize the History callback to stay inside the argument list.
text = re.sub(
    r'"History"\s*->\s*HistoryScreen\(activity\)\s*\{ target -> browserUrl = target; screen = "Browser" \}',
    '"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })',
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
print("PDM final Kotlin compile-call fix applied")

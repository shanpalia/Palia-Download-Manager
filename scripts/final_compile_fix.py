from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# Keep callbacks inside the argument list. This avoids Kotlin's
# "Only one lambda expression is allowed outside a parenthesized argument list" error.
text = text.replace(
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }',
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })'
)
text = text.replace(
    '"History" -> HistoryScreen(activity) { target -> browserUrl = target; screen = "Browser" }',
    '"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })'
)
text = text.replace(
    '"History" -> HistoryScreen(activity)',
    '"History" -> HistoryScreen(activity, onOpenUrl = { target -> browserUrl = target; screen = "Browser" })'
)

# Rename PDM's two-argument download filter so it cannot collide with Material3 FilterChip.
text = text.replace('@Composable private fun FilterChip(selected: Boolean, text: String)', '@Composable private fun PdmFilterChip(selected: Boolean, text: String)')
text = text.replace('FilterChip(true, "All ${files.size}")', 'PdmFilterChip(true, "All ${files.size}")')
text = text.replace('FilterChip(false, "Downloading")', 'PdmFilterChip(false, "Downloading")')
text = text.replace('FilterChip(false, "Completed")', 'PdmFilterChip(false, "Completed")')

KOTLIN.write_text(text, encoding="utf-8")
print("PDM final Kotlin compile-call fix applied")

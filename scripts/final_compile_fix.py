from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/shanpalia/pdm/PdmMainActivity.kt"
text = KOTLIN.read_text(encoding="utf-8")

# The browser and history composables both use four parameters. Keep the calls
# fully parenthesized so Kotlin never sees multiple trailing lambdas.
text = text.replace(
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }) { screen = "Home" }',
    '"Browser" -> BrowserScreen(browserUrl, { browserUrl = it }, { dialogUrl = it }, { screen = "Home" })'
)
text = text.replace(
    '"History" -> HistoryScreen(activity)',
    '"History" -> HistoryScreen(activity) { target -> browserUrl = target; screen = "Browser" }'
)

KOTLIN.write_text(text, encoding="utf-8")
print("PDM final Kotlin compile-call fix applied")

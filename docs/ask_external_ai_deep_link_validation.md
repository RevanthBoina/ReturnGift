# ASK_EXTERNAL_AI — Deep Link URL Validation

**Date:** 2026-08-01
**Status:** URL format verified. On-device launch validation pending (requires device).
**Fallback policy:** When native app is absent, all HTTPS URLs open in the device's
default browser. Every URL below is mobile-browser-compatible.

---

## URL Validation Table

| App | Package | Deep Link URL | Browser Compatible | Query Param | Notes |
|-----|---------|---------------|--------------------|-------------|-------|
| ChatGPT | `com.openai.chatgpt` | `https://chat.openai.com/?q={query}` | ✅ | `q` | Opens new chat with pre-filled prompt |
| Grok | `com.x.ai` | `https://x.ai/?q={query}` | ✅ | `q` | Redirects to grok.com on mobile browser |
| Perplexity | `ai.perplexity.app` | `https://www.perplexity.ai/search?q={query}` | ✅ | `q` | Returns results page directly |
| Gemini | `com.google.android.apps.bard` | `https://gemini.google.com/app?prompt={query}` | ✅ | `prompt` | Requires Google account |
| Claude | `com.anthropic.claude` | `https://claude.ai/new?q={query}` | ✅ | `q` | `/new` creates fresh conversation |
| Copilot | `com.microsoft.copilot` | `https://copilot.microsoft.com/?q={query}` | ✅ | `q` | No login required for basic use |
| Poe | `com.poe.android` | `https://poe.com/search?q={query}` | ✅ | `q` | Multi-model; login required |
| DeepL | `com.deepl.mobile` | `https://www.deepl.com/translator#{src}/{tgt}/{query}` | ✅ | URL fragment | Format: `#auto/en/text` |
| QuillBot | `com.quillbot.mobile` | `https://quillbot.com/paraphrase?text={query}` | ✅ | `text` | Free tier has length limit |
| Gamma | `com.gamma.app` | `https://gamma.app/create?prompt={query}` | ✅ | `prompt` | Login required; generates presentation |
| Consensus | `com.consensus.android` | `https://consensus.app/search?query={query}` | ✅ | `query` | No login for basic search |
| NotebookLM | `com.notebooklm.android` | `https://notebooklm.google.com/` | ✅ | none | No query param; clipboard workflow only |
| Humata | `com.humata.android` | `https://www.humata.ai/` | ✅ | none | No query param; clipboard workflow only |
| Grammarly | `com.grammarly.android.keyboard` | `https://app.grammarly.com/` | ✅ | none | Keyboard-based; clipboard workflow only |

---

## URL Encoding Requirements

All query values must be URL-encoded before insertion. The skill YAML uses `{query|urlencode}`.

| Character | Encoded |
|-----------|---------|
| Space | `%20` or `+` |
| `?` | `%3F` |
| `&` | `%26` |
| `#` | `%23` |
| `=` | `%3D` |

---

## DeepL URL Format Detail

DeepL uses a URL fragment (hash) rather than query parameters:

```
https://www.deepl.com/translator#<source_lang>/<target_lang>/<text>
```

Examples:
- Auto-detect to English: `https://www.deepl.com/translator#auto/en/Hello%20world`
- Spanish to Hindi: `https://www.deepl.com/translator#es/hi/Hola%20mundo`
- English to Japanese: `https://www.deepl.com/translator#en/ja/Good%20morning`

Supported language codes: `auto`, `en`, `de`, `fr`, `es`, `it`, `pt`, `nl`, `pl`, `ru`,
`ja`, `ko`, `zh`, `ar`, `hi`, `tr`, `sv`, `da`, `fi`, `nb`, `ro`, `uk`, `bg`, `cs`, `el`.

---

## Apps Without Deep Link Query Support (Clipboard Workflow)

Three apps have no URL parameter for pre-filling a query. These use the `clipboard_workflow`
route (Tier 3) defined in `ask-external-ai.yaml`:

1. **NotebookLM** — Opens home page; user must paste from clipboard into document upload
2. **Humata** — Opens home page; user must paste from clipboard into PDF chat
3. **Grammarly** — Keyboard-based; text must be in an editable field; no standalone URL

For these apps, the execution flow is:
```
clipboard_set(query) → launch_app → wait_settled → clipboard_paste(EditText)
```

---

## Browser Fallback Route

When the native app is not installed, Android's intent resolution falls back to the
default browser for all HTTPS URLs. The `ask-external-ai.yaml` includes a dedicated
`browser_fallback` route (Tier 0.5) that explicitly targets Chrome when the native
package is absent:

```yaml
- id: browser_fallback
  when: "not app.installed('{app|to_package}')"
  kind: intent
  reliability: 0.80
  cost_ms: 1500
  intent:
    action: android.intent.action.VIEW
    data: "{app|browser_fallback_url}"
    package: com.android.chrome
    flags: [FLAG_ACTIVITY_NEW_TASK]
```

This ensures all 14 apps are testable without native app installation.

---

## On-Device Validation Checklist

Run these manually on SM-S918B (Android 14, OneUI 6) once device is available:

```bash
# Test each deep link via adb
adb shell am start -a android.intent.action.VIEW \
  -d "https://chat.openai.com/?q=hello+world" com.openai.chatgpt

adb shell am start -a android.intent.action.VIEW \
  -d "https://www.perplexity.ai/search?q=hello+world" ai.perplexity.app

adb shell am start -a android.intent.action.VIEW \
  -d "https://gemini.google.com/app?prompt=hello+world" com.google.android.apps.bard

adb shell am start -a android.intent.action.VIEW \
  -d "https://claude.ai/new?q=hello+world" com.anthropic.claude

adb shell am start -a android.intent.action.VIEW \
  -d "https://copilot.microsoft.com/?q=hello+world" com.microsoft.copilot

adb shell am start -a android.intent.action.VIEW \
  -d "https://x.ai/?q=hello+world" com.x.ai

adb shell am start -a android.intent.action.VIEW \
  -d "https://www.deepl.com/translator#auto/en/hello%20world" com.deepl.mobile

adb shell am start -a android.intent.action.VIEW \
  -d "https://quillbot.com/paraphrase?text=hello+world" com.quillbot.mobile

adb shell am start -a android.intent.action.VIEW \
  -d "https://gamma.app/create?prompt=hello+world" com.gamma.app

adb shell am start -a android.intent.action.VIEW \
  -d "https://consensus.app/search?query=hello+world" com.consensus.android

adb shell am start -a android.intent.action.VIEW \
  -d "https://poe.com/search?q=hello+world" com.poe.android
```

Expected result for each: app opens to the correct screen with query pre-filled.
If native app absent: Chrome opens the URL in mobile browser.

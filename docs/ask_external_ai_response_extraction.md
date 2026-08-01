# ASK_EXTERNAL_AI — Response Extraction Validation

**Date:** 2026-08-01
**Purpose:** Document per-app response extraction strategy, known patterns, and fallback
chain. No fixtures available — patterns derived from known app UI architectures.
On-device validation required before promoting any skill to `canary`.

---

## Extraction Strategy Overview

Three methods are defined in `ask-external-ai.yaml` under `response_extraction`:

| Method | ID | Target | Priority | Best for |
|--------|----|--------|----------|----------|
| Large TextView scan | `text_view_scan` | `android.widget.TextView` (min 100 chars) | 1 | Native apps with native text rendering |
| Content description | `content_desc_scan` | `content_desc` matching response/answer/result | 2 | Accessibility-labelled response areas |
| WebView JS injection | `webview_extract` | `android.webkit.WebView` | 3 | Web-based UIs (ChatGPT, Claude, Perplexity) |

Timeout: 15 000 ms. Poll interval: 500 ms. Settle required: 2 consecutive identical reads.

---

## Per-App Extraction Strategy

### ChatGPT (`com.openai.chatgpt`)
- **UI type:** Compose-based native + WebView hybrid
- **Primary method:** `text_view_scan` — response renders in large TextViews
- **Fallback:** `webview_extract` with `document.body.innerText.substring(0, 2000)`
- **Known pattern:** Response appears in a scrollable list; last item is the AI response
- **Streaming:** Response streams token-by-token; wait for `screen_settled` (2 identical reads)
- **Canvas mode:** Content in WebView; JS injection required
- **Extraction selector:**
  ```yaml
  by: class
  value: android.widget.TextView
  min_length: 100
  exclude_hints: ["sign in", "log in", "terms", "privacy", "message chatgpt"]
  last_of_type: true
  ```
- **On-device validation:** ⚠️ Required

### Perplexity (`ai.perplexity.app`)
- **UI type:** Compose-based native
- **Primary method:** `text_view_scan`
- **Sources extraction:** Look for TextViews containing URLs or numbered citations
- **Known pattern:** Answer appears above sources section; sources have `[1]`, `[2]` markers
- **Extraction selector:**
  ```yaml
  by: class
  value: android.widget.TextView
  min_length: 100
  ancestor_content_desc: "(?i)answer|response"
  ```
- **Citations:** Extract separately using `by: text, matches: "\\[\\d+\\].*http"`
- **On-device validation:** ⚠️ Required

### Grok (`com.x.ai`)
- **UI type:** Compose-based native
- **Primary method:** `text_view_scan`
- **Known pattern:** Similar to ChatGPT; response in scrollable conversation list
- **Extraction selector:**
  ```yaml
  by: class
  value: android.widget.TextView
  min_length: 50
  last_of_type: true
  ```
- **On-device validation:** ⚠️ Required

### Gemini (`com.google.android.apps.bard`)
- **UI type:** WebView-heavy (Google's web app wrapped)
- **Primary method:** `webview_extract`
- **JS script:** `document.querySelector('[data-response-index]')?.innerText || document.body.innerText.substring(0, 2000)`
- **Fallback:** `text_view_scan` for any native TextViews
- **Known pattern:** Response in a WebView; accessibility tree may be sparse
- **On-device validation:** ⚠️ Required

### Claude (`com.anthropic.claude`)
- **UI type:** WebView-based (Anthropic web app)
- **Primary method:** `webview_extract`
- **JS script:** `document.querySelector('.font-claude-message')?.innerText || document.body.innerText.substring(0, 2000)`
- **Artifact mode:** Artifact content in separate WebView panel
- **Fallback:** `content_desc_scan` for accessibility-labelled response areas
- **On-device validation:** ⚠️ Required

### Copilot (`com.microsoft.copilot`)
- **UI type:** Compose-based native + WebView
- **Primary method:** `text_view_scan`
- **Known pattern:** Response in chat bubble TextViews; last bubble is AI response
- **Extraction selector:**
  ```yaml
  by: class
  value: android.widget.TextView
  min_length: 50
  last_of_type: true
  ```
- **On-device validation:** ⚠️ Required

### DeepL (`com.deepl.mobile`)
- **UI type:** Native Android (EditText-based)
- **Primary method:** `text_view_scan` targeting the translation output field
- **Known pattern:** Source text in top EditText; translation in bottom TextView/EditText
- **Extraction selector:**
  ```yaml
  by: resource_id
  value: "com.deepl.mobile:id/translation_output"
  fallback:
    by: class
    value: android.widget.EditText
    index: 1  # Second EditText = translation output
  ```
- **On-device validation:** ⚠️ Required

### QuillBot (`com.quillbot.mobile`)
- **UI type:** WebView-based
- **Primary method:** `webview_extract`
- **JS script:** `document.querySelector('#paraphrase-output')?.innerText || document.body.innerText.substring(0, 2000)`
- **On-device validation:** ⚠️ Required

### Gamma (`com.gamma.app`)
- **UI type:** WebView-based (presentation creator)
- **Primary method:** `webview_extract`
- **Note:** Response is a generated presentation, not text — extraction confirms creation
- **Extraction:** Check for slide count or presentation title in DOM
- **JS script:** `document.querySelector('.slide-title, .presentation-title')?.innerText || 'Presentation created'`
- **On-device validation:** ⚠️ Required

### Consensus (`com.consensus.android`)
- **UI type:** WebView-based
- **Primary method:** `webview_extract`
- **JS script:** `Array.from(document.querySelectorAll('.paper-title')).slice(0,5).map(e=>e.innerText).join('\\n')`
- **Citations:** Extract paper titles and DOIs from search results
- **On-device validation:** ⚠️ Required

### Poe (`com.poe.android`)
- **UI type:** Compose-based native
- **Primary method:** `text_view_scan`
- **Known pattern:** Similar to ChatGPT; response in conversation list
- **On-device validation:** ⚠️ Required

### NotebookLM, Humata, Grammarly
- **Extraction:** Not applicable for clipboard workflow — user reads response directly
- **Confirmation:** Skill confirms "Query sent to {app}. Please check the app for the response."

---

## Fallback Chain

```
1. text_view_scan (min_length=100, last_of_type=true)
        ↓ if empty or < 50 chars
2. content_desc_scan (matches "response|answer|result|output")
        ↓ if empty
3. webview_extract (JS: document.body.innerText.substring(0, 2000))
        ↓ if WebView not accessible
4. partial_success → notify_user("I sent your question to {app}, but couldn't read
   the response. Please check the app.")
```

---

## Streaming Response Handling

Most AI apps stream responses token-by-token. The extraction must wait for streaming
to complete before reading. The `response_extraction` config uses:

```yaml
settle_required: 2      # Two consecutive identical reads
poll_interval_ms: 500   # Check every 500ms
timeout_ms: 15000       # Give up after 15s
```

If the response is still streaming at timeout, emit `response_timeout` recovery event:
> "{app} is taking too long to respond. The query was sent — check the app for the answer."

---

## Known Extraction Pitfalls

| App | Pitfall | Mitigation |
|-----|---------|------------|
| ChatGPT | "Message ChatGPT" placeholder text matches min_length | Add `exclude_hints` filter |
| Perplexity | Sources section mixed with answer | Use `ancestor_content_desc` to scope |
| Gemini | WebView accessibility tree sparse | JS injection required |
| Claude | Artifact panel separate from chat | Check both chat and artifact WebViews |
| DeepL | Source text field also matches | Target by index (second EditText) |
| All streaming | Partial response captured mid-stream | `settle_required: 2` prevents this |
| All WebView | JS injection may be blocked by CSP | Fall back to `text_view_scan` |

---

## On-Device Validation Steps

Once device is available, validate each app with this procedure:

```bash
# 1. Send a known query via adb intent
adb shell am start -a android.intent.action.VIEW \
  -d "https://chat.openai.com/?q=what+is+2+plus+2" com.openai.chatgpt

# 2. Wait 10 seconds for response
sleep 10

# 3. Dump UI tree
adb shell uiautomator dump /sdcard/response_test.xml
adb pull /sdcard/response_test.xml fixtures/response_test_chatgpt.xml

# 4. Verify response text appears in tree
grep -i "four\|4\|2+2" fixtures/response_test_chatgpt.xml
```

Expected: response text visible in accessibility tree as a TextView node.
If not found: WebView JS injection path is required for that app.

---
id: ask_external_ai
name: Ask External AI
triggers:
  - "ask chatgpt"
  - "ask grok"
  - "ask perplexity"
  - "ask gemini"
  - "ask ai"
  - "chat with gpt"
---

When the user wants to query an external AI assistant:

1. **open_app** → call open_app(package_name="[ai app package]") to open the AI app
2. **get_screen_info** → confirm the input field is visible
3. **find_and_tap** → tap the message/query input field
4. **input_text** → call input_text(text="[query]") to type the question
5. **system_key** → call system_key(key="enter") to submit
6. **get_screen_info** → read the response
7. **finish** → call finish(summary="[AI app] responded: [first 200 chars of response]")

App package mappings:
- "chatgpt" / "gpt" → com.openai.chatgpt
- "grok" → com.x.ai
- "perplexity" → ai.perplexity.app
- "gemini" / "bard" → com.google.android.apps.bard

Extract from the user's request:
- query = the question or prompt
- app = which AI (default "chatgpt")

- Good: `ask chatgpt about black holes`, `what does grok say about the news`
- Not this playbook: `google [query]` → web-search, `open chatgpt` → open-and-navigate

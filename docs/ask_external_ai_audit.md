# ===========================================================================
# ASK_EXTERNAL_AI — AUDIT REPORT & EXPANDED IMPLEMENTATION
# ===========================================================================
# Repository: https://github.com/RevanthBoina/ReturnGift
# Source file: skill_definitions_v2.yaml (lines ~380-480)
# Playbook:    app/src/main/assets/playbooks/ask-external-ai.md
# Date:        2026-08-01
# ===========================================================================

## PART 1: AUDIT OF EXISTING IMPLEMENTATION
## ===========================================================================

### What EXISTS (DO NOT RECREATE):

#### 1. App Enum (4 entries):
| Slot Value | Package                    | Has Route? | Has Fixture? | Has Compatibility? |
|------------|----------------------------|------------|--------------|---------------------|
| chatgpt    | com.openai.chatgpt         | ✅ YES      | ❌ NO (null)  | ❌ NO (missing)      |
| grok       | com.x.ai                   | ❌ NO       | ❌ NO         | ❌ NO                |
| perplexity | ai.perplexity.app          | ✅ YES      | ❌ NO (null)  | ❌ NO                |
| gemini     | com.google.android.apps.bard| ❌ NO       | ❌ NO         | ❌ NO                |

#### 2. Execution Routes (2 of4 needed):
- ✅ `chatgpt_web` — Intent: https://chat.openai.com/?q={query}
- ✅ `perplexity_web` — Intent: https://www.perplexity.ai/search?q={query}
- ❌ `grok_web` — MISSING (no route for Grok)
- ❌ `gemini_web` — MISSING (no route for Gemini)
- ✅ `ui_generic` — Fallback UI automation (catches all apps)

#### 3. Playbook (ask-external-ai.md):
- ✅ 6 triggers defined
- ✅ App package mappings documented
- ✅ Generic UI steps (open_app → get_screen_info → find_and_tap → input_text → submit → read → finish)
- ❌ No per-app specific workflows
- ❌ No capability-specific flows (summarize, translate, rewrite, etc.)

#### 4. Slots (2 defined):
- ✅ `query` — string, required, max_len 2000
- ✅ `app` — enum with inference_hints

#### 5. Screen Signatures:
- ✅ `chatgpt_home` — package_equals: com.openai.chatgpt
- ✅ `chatgpt_conversation` — package_equals: com.openai.chatgpt
- ❌ No signatures for grok, perplexity, gemini

#### 6. Recovery:
- ✅ auth_wall → abort + notify
- ✅ rate_limited → abort + notify
- ✅ app_not_installed → delegate to search_install_app
- ✅ all_routes_failed → escalate to llm_open_loop

#### 7. Routing:
- ✅ Priority: 70
- ✅ 10 triggers defined
- ✅ Anti-triggers for web_search, open_app
- ✅ Required entities: [query]

### What's MISSING (MUST ADD):

#### Critical Gaps:
1. **Grok execution route** — User can select "grok" but no intent/UI path exists
2. **Gemini execution route** — User can select "gemini" but no intent/UI path exists
3. **Per-app compatibility entries** — No tested_version_name, min_sdk, etc.
4. **Per-app fixtures** — All tree_hash: null, no screen XML files captured
5. **Per-app screen signatures** — Only ChatGPT has signatures
6. **Capability slots** — No support for summarize, translate, rewrite, image_gen, etc.
7. **Deep link validation** — No verification that URLs actually work
8. **Response extraction** — No logic to read/extract AI responses
9. **Multi-turn conversation** — No support for follow-up questions
10. **Clipboard integration** — No share-to-clipboard or paste-from-clipboard

#### Feature Gaps (from use case analysis):
- Content sharing TO AI apps (share intent)
- Screenshot explanation workflows
- Image generation prompts
- Code assistance flows
- Document analysis (PDF upload)
- Voice query support
- Canvas/artifact interaction (Claude, ChatGPT)
- Presentation generation (Gamma, Tome)
- Translation workflows (DeepL)
- Grammar checking (Grammarly, QuillBot)

# Skill Integration Analysis

**Updated:** 2026-08-01
**Source state:** Full Android source committed to `source_repo/`.
**Scope:** How `skill_definitions_v2.yaml` (19 production skills) relates to the committed
Kotlin skill layer (`BuiltInSkills.kt`, `SkillRegistry`, `SkillExecutor`, `Skill.kt`).

---

## Two distinct skill layers

### Layer 1 — Kotlin BuiltInSkills (committed, operational)

Located at `app/src/main/java/com/returngift/agent/agent/skill/`.

| File | Role |
|------|------|
| `Skill.kt` | Data model: `Skill`, `SkillStep`, `SkillParameter`, `SkillResult`, `SkillCategory` |
| `SkillRegistry.kt` | In-memory registry; `loadBuiltInSkills()` called at startup; trigger-pattern matching |
| `SkillExecutor.kt` | Deterministic step runner; retries; falls back to LLM agent loop on failure |
| `BuiltInSkills.kt` | 9 app-agnostic utility skills (see table below) |

**Registered built-in skills:**

| Skill ID | Category | Steps saved | Trigger patterns |
|----------|----------|-------------|-----------------|
| `search_in_app` | INPUT | 5 | _(none — too ambiguous, agent loop preferred)_ |
| `submit_form` | INPUT | 4 | submit, send message, press send |
| `dismiss_popup` | DISMISS | 3 | dismiss, close popup, close dialog |
| `scroll_and_read` | GENERAL | 10 | read the page, scroll and read, read all content |
| `copy_screen_text` | GENERAL | 3 | copy text, extract text, read screen |
| `accept_permission` | DISMISS | 3 | accept permission, allow permission, grant access |
| `swipe_gesture` | NAVIGATION | 2 | _(none — too ambiguous, agent loop preferred)_ |
| `go_back` | NAVIGATION | 1 | go back, press back, navigate back |
| `wait_for_content` | GENERAL | 5 | wait for content, wait for loading, wait for response |

**Key design decisions in the Kotlin layer:**
- Compound tasks (`and`, `then`, `after`) bypass skill matching and go straight to the agent loop.
- `userFacing: Boolean` controls whether a skill appears in the Task UI (currently all built-ins have `userFacing = false`).
- `SkillExecutor` resolves `{param}` placeholders in step params before tool dispatch.
- On non-optional step failure after retries, executor returns `fallbackGoal` string for the LLM to continue.

### Layer 2 — skill_definitions_v2.yaml (19 production skills, all `status: draft`)

These are **slot-extractor + intent/UI-automation recipes** for specific apps. They are
not yet wired into the Kotlin `SkillRegistry` — they represent the planned production
skill contract, pending fixture capture and device validation.

**Coverage by domain:**

| Domain | Skills |
|--------|--------|
| communication | send_message, open_conversation, make_phone_call |
| info_academic | ask_external_ai, web_search, view_assignments |
| navigation | search_place |
| health_lifestyle | book_ride |
| entertainment | search_video |
| app_management | search_install_app |
| utility | set_alarm, navigate_settings, open_file_in_drive, record_audio |
| social | open_linkedin_feed |
| default_app | open_app |
| developer_tools | search_repository |
| content_creation | create_design, create_video_project |

**Note on `search_repository`, `create_design`, `create_video_project`:** These three skills
carry an explicit `scope_note` in the YAML indicating that the tier decision (local
FunctionGemma Action Skill vs. cloud Deep-Agent) is deliberately deferred. All three are
`status: draft` and promotion is blocked by the `scope_note` until the decision is made.
This is an intentional design deferral, not an architectural gap.

---

## Gap analysis

### What exists in the committed source

- All 18 playbooks present in `app/src/main/assets/playbooks/` — including `send-message.md`,
  `open-and-search.md`, `open-and-navigate.md`, and 15 others.
- `BuiltInSkills.kt`, `SkillRegistry.kt`, `SkillExecutor.kt`, `Skill.kt` — all present.
- Full tool layer (`app/src/main/java/com/returngift/agent/tool/`), task loop
  (`TaskOrchestrator.kt`), and accessibility service (`ClawAccessibilityService.java`) — all present.
- `skill_definitions_v2.yaml` — 19 skills, all `status: draft`, all 22 fixture entries
  explicitly set to `tree_hash: null  # TODO: generate from real fixture file`.

### Missing dependency: fixture XML files

All 22 fixture entries in `skill_definitions_v2.yaml` reference paths under `fixtures/`
that do not yet exist in the repository. The `fixtures/` directory currently contains only
`README.md`. The 21 distinct XML files must be captured from a real device before any skill
can be promoted from `draft` to `canary`.

**Referenced paths (none present):**

| Path | Skill(s) |
|------|----------|
| `fixtures/screen_1.xml` | send_message, open_conversation |
| `fixtures/screen_2.xml` | open_linkedin_feed |
| `fixtures/screen_3.xml` | ask_external_ai |
| `fixtures/screen_4.xml` | navigate_settings |
| `fixtures/screen_12.xml` | make_phone_call |
| `fixtures/screen_14.xml` | web_search |
| `fixtures/screen_15.xml` | open_file_in_drive |
| `fixtures/screen_16.xml` | search_install_app |
| `fixtures/screen_17.xml` | view_assignments |
| `fixtures/screen_18.xml` | search_place |
| `fixtures/screen_24.xml` | send_message |
| `fixtures/screen_25.xml` | set_alarm |
| `fixtures/screen_27.xml` | book_ride |
| `fixtures/screen_30.xml` | create_video_project |
| `fixtures/screen_31.xml` | send_message |
| `fixtures/screen_32.xml` | send_message |
| `fixtures/screen_33.xml` | search_video |
| `fixtures/screen_34.xml` | record_audio |
| `fixtures/screen_35.xml` | create_design |
| `fixtures/screen_36.xml` | search_repository |
| `fixtures/screen_39.xml` | open_app |

Capture command (SM-S918B, Android 14, OneUI 6):
```bash
adb shell uiautomator dump /sdcard/screen_N.xml
adb pull /sdcard/screen_N.xml fixtures/screen_N.xml
sha256sum fixtures/screen_N.xml
```
After capture, replace each `tree_hash: null` with the real `sha256:...` value.
See `fixtures/README.md` for the full list and instructions.

### tree_hash status

All 22 fixture entries carry `tree_hash: null  # TODO: generate from real fixture file`.
This is an intentional, transparent placeholder — there are no hardcoded fake hashes and
no entries silently missing the field. The `null` values will be replaced with real
SHA-256 hashes once the fixture files are captured and committed.

### Integration path: YAML → Kotlin

The YAML skills are not yet loaded by `SkillRegistry`. Two options:

1. **Parse YAML at runtime** — load `skill_definitions_v2.yaml` from assets, convert each
   skill's `execution.routes` into `SkillStep` sequences, register via `SkillRegistry.register()`.
   Pros: single source of truth. Cons: YAML parsing overhead at startup; schema mismatch risk.

2. **Generate Kotlin from YAML** — build-time codegen step produces typed `Skill` objects
   from the YAML. Pros: type-safe, zero runtime parsing. Cons: adds build step.

Neither is implemented yet. The Kotlin layer currently only runs the 9 `BuiltInSkills`.

---

## Accuracy verdict

`skill_definitions_v2.yaml` accurately describes the intended production skill contract.
The committed Kotlin source is consistent with the architecture it assumes. No contradictions
found between the YAML schema and the Kotlin data model — `SkillStep`, `SkillParameter`,
and `SkillResult` are compatible with the YAML `execution.routes` structure.

**This document should be re-verified after:**
- Fixture XMLs are captured, committed, and `tree_hash` values are filled.
- YAML-to-Kotlin wiring is implemented.
- Tier decisions are made for `search_repository`, `create_design`, and `create_video_project`.

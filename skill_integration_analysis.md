# Skill Integration Analysis

**Updated:** 2026-07-20
**Source state:** Full Android source committed to `source_repo/` (Finding 1 resolved).
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

### Layer 2 — skill_definitions_v2.yaml (19 production skills, draft status)

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
| developer_tools | search_repository _(scope unresolved)_ |
| content_creation | create_design, create_video_project _(scope unresolved)_ |

---

## Gap analysis

### What exists in the committed source

- `send-message.md`, `open-and-search.md`, `open-and-navigate.md` playbooks — **present** in
  `app/src/main/assets/playbooks/` (e.g. `send-message.md`, `open-and-search.md`,
  `open-and-navigate.md`).
- `BuiltInSkills.kt` — **present and committed**.
- All other Kotlin source (tool layer, task loop, accessibility service) — **present**.

### What is still missing

| Item | Blocker |
|------|---------|
| 21 fixture XML files (`fixtures/screen_N.xml`) | Requires ADB capture from SM-S918B (Finding 4) |
| `tree_hash` values in `skill_definitions_v2.yaml` | Blocked by fixture capture |
| Wiring of YAML skills into `SkillRegistry` | Design decision pending (see below) |
| Promotion of any skill from `draft` to `canary` | Blocked by fixtures + tree_hash |

### Integration path: YAML → Kotlin

The YAML skills are not yet loaded by `SkillRegistry`. Two options:

1. **Parse YAML at runtime** — load `skill_definitions_v2.yaml` from assets, convert each
   skill's `execution.routes` into `SkillStep` sequences, register via `SkillRegistry.register()`.
   Pros: single source of truth. Cons: YAML parsing overhead at startup; schema mismatch risk.

2. **Generate Kotlin from YAML** — build-time codegen step produces typed `Skill` objects
   from the YAML. Pros: type-safe, zero runtime parsing. Cons: adds build step.

Neither is implemented yet. The Kotlin layer currently only runs the 9 `BuiltInSkills`.

### Scope-unresolved skills

Three skills in `skill_definitions_v2.yaml` carry `scope_note: SCOPE UNRESOLVED`:

| Skill | Issue | Recommendation |
|-------|-------|----------------|
| `search_repository` | Outside 8-domain taxonomy; read-only, low complexity | Plausible as local Action Skill |
| `create_design` | WebView inaccessibility; multi-step creative workflow | Cloud Deep-Agent tier |
| `create_video_project` | Multi-minute stateful; no deep links | Cloud Deep-Agent tier |

These must not be wired into `SkillRegistry` until the tier decision is made (Finding 7).

---

## Accuracy verdict

`skill_definitions_v2.yaml` accurately describes the **intended** production skill contract.
The committed Kotlin source is consistent with the architecture it assumes (tool layer,
accessibility service, agent loop). No contradictions found between the YAML schema and
the Kotlin data model — `SkillStep`, `SkillParameter`, and `SkillResult` are compatible
with the YAML `execution.routes` structure.

**This document should be re-verified after:**
- Fixture XMLs are committed and `tree_hash` values are filled (Finding 4/5).
- YAML-to-Kotlin wiring is implemented.
- Scope decisions are made for the three unresolved skills (Finding 7).

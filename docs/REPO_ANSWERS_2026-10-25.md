# Answers recorded + the three unknowns, resolved from code

Date: 2026-10-25 · commit `7acea8c` (v3.5.1) · companion to `REPO_QUESTIONS_2026-10-25.md`

You answered six of the sixty questions. Three of your answers were "I don't know" / "not
sure", so this document replaces those three with **what the code actually does** — facts
with `file:line`, no judgement. What is left after the facts is a smaller question, printed
at the end of each section.

## Answers given

| # | Question | Your answer |
|---|---|---|
| D5–D12 | Which workflow format is authoritative | **Unknown** — you don't know which the app follows |
| D37 | Should PRs be gated | **No PR gate, but the release path must run tests** |
| D1–D4, D30–D33 | Which inherited subsystems are in scope | **Unsure** — not sure what still works |
| D32 | Encrypt provider API keys too | **Unknown** — never considered |
| D13–D19, D50–D51 | Restructure the agent loop | **Later** — revisit after the workflow question |
| D2, D56–D60 | Personal tool vs product | **A product for other people's phones** |

What that changes: the workflow question is the one blocking the loop question, and
"product" makes OEM/device variance and permission scope matter rather than optional.

---

## RQ1 — Which of the four workflow formats the app follows

**Answer: it depends on which tier handles the task. There is no single authority, and the
new plan is enforced in only one of the three paths.**

### The routing tiers (verified)

Entry is `ChannelSetup`/`AppViewModel` → `TaskOrchestrator.startNewTask`, which calls
`pipelineRouter.route(task)` at `TaskOrchestrator.kt:327`. The route decides the tier:

| Tier | Route | Code | What executes |
|---|---|---|---|
| 1 | `DirectIntent`, `DirectTool` | `TaskOrchestrator.kt:343–453` | deterministic intent/tool, no LLM |
| 2 | `Skill` | `TaskOrchestrator.kt:454` → `SkillExecutor.execute(...)` | **YAML skill** (`skill_library/skills/*.yaml`) |
| 2 | `Redirect`, `Chat` | `:535`, `:...` | skill redirect / chat answer |
| 3 | `AgentLoop` | `TaskOrchestrator.kt` → `DefaultAgentService.executeTask` | plan-first → LLM loop |

### What happens inside tier 3 (verified, in order)

| Step | Line | Notes |
|---|---|---|
| Plan computed + displayed | `DefaultAgentService.kt:553–605` | `PlanFirst.plan(...)`, no model call |
| Plan contract built | `:607–630` | `PlanContract.from(...)` / resume from checkpoint |
| Structured routine match | `:752–757` | **only** when intent is `DEVICE_AUTOMATION`; `runStructuredRoutine(...)` then **`return`** |
| Playbook injection | `:780–788` | **only** when `config.provider == LOCAL` |
| Micro-steps | `:939`, `:1118`, `:1176`, `:1799` | `AgentPreferences.enforceMicroSteps` defaults **true** (`AgentPreferences.kt:41`) |
| Plan-contract gate | `:1386` | per tool call |
| Plan-contract observe | `:1536` | per tool result |

### The consequence you were asking about

- **Tier 2 (YAML skill):** `SkillExecutor` runs the skill directly. `DefaultAgentService` is
  never entered, so there is **no plan, no plan contract, no checkpoint** on that path.
- **Tier 3 with a structured routine match:** the plan **is displayed**, then
  `runStructuredRoutine` runs and **returns** — the contract at `:607` never observes those
  steps. On that path the displayed plan is informational, not enforced.
- **Tier 3 without a routine:** the plan is displayed **and** enforced (gate + evidence +
  `finish` refusal).
- **Playbooks only influence the local-model path.** With a cloud provider the 20 markdown
  playbooks are never injected; the model gets the tool list and the plan line instead.
- **A YAML skill outranks a plan** for the same request, because tier 2 is decided before
  tier 3 ever starts.

### Which files are actually loaded, and which are dead

| Asset | Count | Loaded by | Status |
|---|---|---|---|
| `assets/skill_library/skills/*.yaml` | 19 | `YamlSkillLoader` (`SKILLS_DIR = "skill_library/skills"`, `YamlSkillLoader.kt:13`) | **live** |
| `assets/playbooks/*.md` | 19 | `PlaybookManager` (scans `*.md` only) | **live, local model only** |
| `assets/playbooks/ask-external-ai.yaml` | 1 | — | **dead** (`.yaml` in a `.md` directory) |
| `assets/skills/handle-interrupt.md` | 1 | — | **dead** (no loader reads `assets/skills`) |

14 task names exist in both live sets (`send-message`, `set-alarm`, `make-phone-call`,
`open-conversation`, `search-repository`, `record-audio`, `navigate-settings`,
`view-assignments`, `book-ride`, `create-design`, `create-video-project`,
`open-file-in-drive`, `open-linkedin-feed`, `search-install-app`). The YAML copy wins on the
skill path; the markdown copy is only ever used by the local model. They are not kept in
sync by anything.

**The question that is left:** given the above — should the *plan* become the single
description (with skills/routines/playbooks as *executors* of plan steps), or should tier 2
and routine paths keep bypassing it? Both are coherent; they imply different work.

---

## RQ2 — What is reachable (the "not sure what still works" question)

**Answer, by feature, from the code:**

| Feature | Switch | Default | Reachable? |
|---|---|---|---|
| LAN config server (nanohttpd) | `KVUtils.isConfigServerEnabled()` `KVUtils.kt:168` | **false** | only if you enabled it |
| Cloud deep agent | `isCloudDeepAgentEnabled()` `:178` | **false** | only if you enabled it |
| External automation (Tasker/MacroDroid) | `isExternalAutomationEnabled()` `:173` | **false** | only if you enabled it; **no caller check** beyond that flag |
| Channels (Discord / Telegram / WeChat) | bot tokens in `KVUtils` (`:151/155/159`) | empty | wired via `ChannelSetup` ← `AppViewModel.kt:37`; live if a token is set |
| Notification listener | accessibility-style bind | — | bound to `BIND_NOTIFICATION_LISTENER_SERVICE`; feeds `AutoReplyManager` |
| Boot receiver | — | — | **no-op** (`BootReceiver.kt` logs and returns) |
| Keep-alive job | — | — | restarts `ForegroundService` every 15 min; starts no task |

So "everything is off by default" is literally true for the three network-facing features;
the channel code is live but dormant without tokens.

**Left open:** which of those you *want* to keep for a product (channels and the LAN server
are the two that turn a personal tool into an attack surface — but that is your call, not a
finding).

---

## RQ3 — Is plaintext API-key storage actually required?

**Answer: there is no code path today that reads a provider key before the device is first
unlocked**, so the usual "background work needs plaintext" justification is not evidenced
here.

Checked:
- `BootReceiver` starts nothing (`BootReceiver.kt:19–24`, a deliberate no-op).
- `KeepAliveJobService` only restarts the foreground service; it does not start tasks.
- The three background features that could run unattended all default to **false** (above).
- A Keystore key created without user-authentication binding is readable after first unlock
  — i.e. whenever any of the above could realistically run.

What that leaves: **the asymmetry is currently unexplained by the code** — not proven wrong,
just not justified by anything I can find.

**Left open:** whether to move the keys behind the store you already built, and whether the
migration should be lazy (on first read) or at startup.

---

## What was implemented from your answers

You chose "no PR gate, but the release path must run tests", so `.github/workflows/release.yml`
now runs `./gradlew testDebugUnitTest` **before** signing and publishing, with:

- the full test log appended to the job summary on failure,
- the reports uploaded as an artifact on failure,
- a printed totals line (`tests / failures / errors / skipped`) parsed from the JUnit XML.

Consequence, stated plainly: **the next tag can now fail on tests.** If any of the 74 test
files are stale (they have not been executed by CI in this repo's history), the first tag
after this change will be the one that finds out. That is the intended behaviour of the
option you picked — but it is a real risk to the next release, not a formality.

Nothing else was changed. The loop is untouched (your "later"), no files were moved, no
subsystems were removed.

---

## Standing question list, now ranked by blocker

1. **Workflow authority** (RQ1): plan-as-description vs four parallel formats — blocks the
   loop restructure you deferred.
2. **Scope** (RQ2): which dormant subsystems stay in a product build.
3. **Key storage** (RQ3): lazy or startup migration, or leave it.
4. **Product hardening** (your "product" answer): PR gating, minify, ABI split, permissions —
   all of these matter more for other people's phones than for yours, and all are currently
   in their permissive setting.
5. Everything else in `REPO_QUESTIONS_2026-10-25.md` (D20–D29, D34–D60) is still unanswered.

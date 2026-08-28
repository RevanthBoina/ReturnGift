# Tier-1 Intent-Matching Architecture

Status: **living** (owner: RevanthBoina, 2026-08-28)
Scope: the zero-LLM deterministic layer — `PipelineRouter.route()` → `TaskParser.parse()`
→ `Route.DirectIntent` / `Route.DirectTool` → (no match) Tier-2 skills → Tier-3 agent loop.

This document is the single source of truth for how Tier 1 decides *what* and *when* to
handle a task deterministically. It supersedes ad-hoc regex decisions scattered across the
parser. **A new intent is added by following the checklist in [How to add an intent](#9-how-to-add-a-new-intent).**

---

## 1. Normalization policy

ONE normalization step, applied to the raw task text before **every** matcher:

1. `trim()`.
2. `lowercase()` (the parser's language is English-only by decision D2 — see ADR).
3. Collapse runs of whitespace to a single space.
4. Strip **trailing politeness/particles** (English only): `please`, `pls`, `thank you`,
   `thanks`, `thx`, `ok`, `okay`. These are removed only when they appear as trailing words.
5. Strip trailing terminal punctuation (`.` `?` `!`).

This fixes the historical A9 defect: several matchers ran against the pre-lowercased string
(`matchSendMessage`/`matchOpenUrl`/`matchOpenApp` used IGNORE_CASE against the original) while
others ran against a lowercased copy. Now all matchers receive the same normalized `String`.

### Light punctuation tolerance

Internal punctuation is preserved (it matters for numbers and URLs), but adjacent trailing
punctuation on captured groups is trimmed. Commas/semicolons inside a number or URL are kept.

## 2. Anchoring & tolerance policy

| Anchor style | Meaning | Applied to (justification) |
|---|---|---|
| `find()` (substring, prefix-tolerant) | Match may appear anywhere in the normalized string. | `open_url`, `send_message`, `alarm`, `timer`, `screenshot`, `flashlight`, `camera` — these intents tolerate leading filler ("please open …", "hey, could you …"). |
| `$`-suffix-capture | Match may run to end; trailing literal text is swallowed into a captured group (loose suffix). | `open_app` (`open|launch|start …`) — trailing words are part of the app-name guess and captured, so a following verb still prevents anchoring. |
| `^`-start anchor + fixed filler set | Verb must appear at the **start** (after politeness); only a small fixed set of fillers allowed between verb and number. | `call`, `sms` — see FIX 2. A non-start verb ("on whatsapp call mom") intentionally falls through to Tier 2/3. |
| exact match (post-normalization) | normalized string must equal one of a small closed set. | `back`, `home` — ultra-short commands; trailing context is a different intent. Politeness is stripped first so `go back please` still matches. |

This replaces the inconsistent A8 state (`SEND_MESSAGE_PATTERN`/`OPEN_APP_PATTERN` were
irregularly anchored; `back`/`home` were exact-only; others used bare `find`).

**English-only (D2).** Non-English alternations were removed: `打電話|打畀|致電`
(`call`), `鬧鐘|叫醒` (`alarm`), `計時` (`timer`), `截圖|影相` (`screenshot`), `打開|開`
(`url`/`open_app`), `返回|返回主頁` (`back`/`home`), `設定` (`settings`), `，然後`
(`open_app` guard). Removing the `res/values-ja|zh` string folders is a SEPARATE, out-of-scope
follow-up (D2c).

## 3. Precedence & conflict rule

**First-match-wins**, in the documented intent order below. New intents are inserted at a review
position in this table and the ordering table is updated. Every pair of intents whose keywords
overlap MUST pin the winner with ≥1 corpus utterance.

| Order | Intent | Matches (normalized) |
|---|---|---|
| 0 | compound guard | contains ` and ` / ` then ` / ` after ` → AgentLoop (over-broad by design, see A10 limitation below) |
| 1 | `call` | `call|phone|ring|dial` at start (+ fixed filler) then a 3–15 digit number → `ACTION_DIAL` |
| 2 | `send_message` | `send|message|text`<content>` to `contact`(` on `app`)? — content/contact non-contextual & non-email & non-numeric |
| 3 | `sms` | `sms|text|message` + number / `send an sms to N` — start-ish, but a trailing name makes it ambiguous → FALLBACK |
| 4 | `alarm` | `set|create`? `alarm|wake up` ... `at|for` `<time>` |
| 5 | `timer` | `set|start`? `timer|countdown` ... `<duration> <unit>` |
| 6 | `screenshot` | `screenshot|screencap` |
| 7 | `flashlight` | `flashlight|torch` + `on|off` or bare toggle |
| 8 | `camera` | (bare `camera`) or `open|start camera(…)` |
| 9 | `back` / `home` | exact or `press …` phrase |
| 10 | `open_url` | `open|go to|visit|navigate to` + `https?://…` |
| 11 | `open_settings` | `settings` + keyword, or `open|go to settings` |
| 12 | `open_app` | `open|launch|start the <app>` (suffix-canned) |

### Reference overlaps (pinned in the corpus)
- **sms vs send_message** on `send a text to <number>` → `sms` wins (numeric recipient is
  inherently an SMS). `send text to Axel` → FALLBACK (bare vector `text`, no real message, and
  `x` one-letter guard — A5).
- **camera vs open_app** on `open camera` → `camera` (media intent) wins.
- **open_settings vs open_app** on `open wifi settings` → `open_settings`.
- **back/home** exact-matches take priority over `open_app` so `home` is never treated as an app.

### Compound-guard limitation (A10)
The guard is a crude substring test (`and`/`then`/`after` with surrounding spaces). It is
over-broad (any sentence containing the bigram, e.g. `open entrance and then left`) and
under-broad (punctuation variants such as `a…, and…`). D1 keeps it as the routing behavior
because it is cheap and safe (funnel to the agent loop); the wide cases are **pinned in the
golden corpus as FALLBACK** and this limitation is recorded here and in BACKLOG.md.

## 4. Number / entity extraction rules

**Call & SMS numeric eligibility (A6/A7).** A phone number is recognized when it contains
**3..15 digits** after stripping spaces, `-`, `+`, `(`, `)`. Outside that range the intent
returns `null` and falls through:

- 1–2 digits: too short to be a real dialable number → fall through ("call 1", "call 12").
- >15 digits: not a real-world phone number → fall through ("call 1234567890123456").
- The dialed UI (`ACTION_DIAL`) is the system confirmation; this is why an unanchored match is
  cheap (the number shown is confirmable) but a 3-digit match like `911` is **deliberately eligible**
  — it is a real, dialable number, and the user confirms before the call connects.

After eligibility, the raw number is canonicalized (remove spaces/`-`/`(`/`)`) for the `tel:`
URI. Extension handling: **not supported** — a trailing `xNN` / `ext NN` is silently dropped.
This is a documented known limitation: the dialer opens pre-filled with the base number and the
user is expected to add the extension if needed.

**Real-ish range guard.** `call 0`/`call 1234567890123456` are rejected as above; this is the
"luckily not designed" fix from A6.

## 5. Risk-tier & confirmation rule (Phase 1 blast-radius)

| Intent | Output | Reversible in app? | Confirmation surface | Allow-list? |
|---|---|---|---|---|
| call | `ACTION_DIAL` (system UI) | user can cancel in dialer | dialer UI itself | n/a — system UI |
| sms | `ACTION_SENDTO` (compose UI) | cancellable before send | SMS compose UI | n/a — system UI |
| send_message | taps send in 3rd-party app (a11y) | NO — not undoable (by design) | **none — must add** (D3) | ✅ per-app |
| alarm/timer | system clock UI | cancellable | system UI | n/a |
| screenshot | screen capture (a11y) | reversible (image deleted) | none (low risk) | n/a |
| back/home | system key | reversible (nav) | none | n/a |
| open_url | browser | reversible | none | n/a (system browser) |
| open_settings | settings intent | reversible (back) | none | n/a |
| open_app | launch app | reversible (undo/back) | none | n/a |
| camera | camera intent | reversible (back) | none | n/a (system camera) |
| flashlight | torch toggle | reversible (toggle) | none | n/a |

**The rule (normative):** irreversible + **no system confirmation surface** ⇒ requires an
app-level pre-action confirmation (or is excluded from Tier 1). Everything reversible / UI-only
⇒ no confirmation (to protect latency).

Only **send_message** falls into the "irreversible + no surface" category today, so it is the
only Tier-1 intent that must confirm before executing. `call`/`sms` are irreversible in the real
world but the dialer/SMS compose UI is the system confirmation (per D3 doctrine), so no
app-level confirmation is needed.

## 6. Risk-tier table (assigned)

| Intent | Risk tier | Reverse | Confirmation |
|---|---|---|---|
| call | Medium | system-UI | dialer |
| sms | Medium | system-UI | compose |
| send_message | **High** | **none** | **D3 pre-send confirm** |
| all others (alarm, timer, screenshot, back, home, open_url, open_settings, open_app, camera, flashlight) | Low | yes / UI-only | none |

## 7. Confirm-before-send (D3)

Tier-1 `send_message` must do a non-blocking confirm on the chat surface: a confirm chip/card
**tap-to-send**, **5-second auto-cancel** (safe default). Reuses the existing SYSTEM-card path.
Cold fallback: the existing warm `ConfirmDialog` flow.

## 8. Observability (on-device aggregate)

`tier1_hit_<intent>`, `tier1_total`, `tier3_fallback_total`, `tier1_fp_<intent>` are all
**aggregate counters** written via `KVUtils` / `XLog` and displayed in the debug page
(`assets/web/debug.html`). Intent names are a fixed closed vocabulary — no raw utterance
text is ever logged.

## 9. How to add a new intent

Checklist (each step in its own commit):

1. Blast-radius row added (reversible? confirmation? allow-list?).
2. Matching rule + position in the precedence table (P0/P1).
3. ≥5 positive + ≥5 negative utterances added to `fixtures/tier1_golden_utterances.jsonl`.
4. TaskParser matcher implemented + own test block/file.
5. `./gradlew testDebugUnitTest` green.
6. Spec (this doc) updated (order + risk-tier table).
7. QA_CHECKLIST.md section entry (E2E via DebugTaskReceiver).

## 10. Safety gates on Tier-1 DirectTool (A4 / FIX 11)

Tier 1 never enters the agent loop, so the gates that live there
(`AllowListToolGate`, `SafetyInterceptor`, `PersonalContentConsentGuard`) are **not** applied
during Tier-1 execution. The following real gates cover the Tier-1 DirectTool path instead:

- **Global blocklist + payment gate:** `PipelineRouter.executeTool()` runs
  `SafetyInterceptor.checkGlobalBlocklist()` on the concatenated tool params before anything
  else, so sensitive content (OTP / one-time password / CVV / pin code / "password is" —
  mirroring the send_message skill's `blocklist_patterns`) never reaches a Tier-1 tool call.
  `ToolRegistry.executeTool()` additionally runs `SafetyInterceptor.check()` unconditionally
  before every call, so its global payment gate (`checkPaymentSafety`) and blocked
  payment-package gate apply too. (The skill-YAML blocklist itself needs an `activeSkillId`,
  which Tier 1 never has — `checkGlobalBlocklist` closes that gap; keep the two lists in sync.)
- **Per-app allow-list:** `PipelineRouter.executeTool` now runs the per-app allow-list check
  (`AppAllowListGuard.checkAndRecord`) for tools that target a specific third-party app —
  currently `send_message` (its `app` param). `Allowed` and `FirstTime` (default-ON) proceed;
  `Blocked` returns an error and no tool executes. The decision half is the pure-JVM-testable
  `PipelineRouter.allowListBlockError` (pinned by `PipelineRouterAllowListGateTest`).
- **Flashlight tool** (`FlashlightTool`, `CameraManager.setTorchMode`) is a live registered
  tool; no flash → honest "This device has no flashlight." error (never a silent no-op).

**Why DirectIntent paths are exempt from the per-app allow-list:** `DirectIntent` targets
*system* UIs — the dialer (`ACTION_DIAL`), SMS compose (`ACTION_SENDTO`), settings, and the
browser. The per-app allow-list governs acting *inside* a third-party messaging app
(`send_message`); system pickers are the OS's own intent surface and are not a 3rd-party app to
allow-list. This is the same distinction A4 draws and is pinned by the QA T1.5 cases.

## 11. TaskShortcuts disposal (C1)

The dead `TaskShortcuts` object (zero callers) is **deleted** per the four-signal gate after
its live-value behaviors were ported into Tier 1:
- `openCamera` → `matchCamera` (DirectIntent `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA`)
- `setTorch` → `FlashlightTool` (registered; parser emits `toolName="flashlight"`)
- settings / back / home / screenshot → respective live matchers (incl. the revived
  `press back` / `press home` phrasings and `go to settings`)

The 34-keyword `KNOWN_PACKAGES` map is **NOT revived** (A5 / C1): its one-letter-key
substring hazard (`x` → Twitter) made word-boundary matching the only safe option, and the
live `open_app` pattern + `OpenAppTool.resolveAppNameStatic` fuzzy launch already cover app
opens. "send text to Axel" / "check xbox" are pinned as FALLBACK in the corpus.

## 12. TaskClassifier disposal (A2)

The orphan LLM `TaskClassifier` (object, zero callers; exposed only
`buildClassifierPrompt` + `parseResponse`) is **deleted** per the four-signal gate. The live
pre-loop classifier is the deterministic `agent/exec/TaskIntentClassifier`; TaskClassifier was
never wired and its LLM-classification role is out of scope for the Tier-1 deterministic layer.
(Future LLM pre-classification is a separate routing decision, not this work.)

## 13. Bounded execution & cancellation (C5 / FIX 5 / FIX 9 / FIX 10)

Every non-LLM execution path must be bounded so one hung run cannot wedge the session lock
("Another task is still running" forever).

**Shared helper:** `agent/exec/BoundedExecution.runBounded(wallClockMs) { body }` — the ONE
wall-clock wrapper. It runs `body` on a daemon worker and returns `Completed / TimedOut /
Failed`. On `TimedOut` the future is cancelled and the caller abandons the invocation (the
stuck worker keeps running to completion in the background but no longer can block the task
pipeline). Pure JVM, unit-tested.

**Tier-1 DirectTool** (FIX 10) wraps `PipelineRouter.executeTool` in `runBounded` with a
60 s default (`TaskOrchestrator.directToolTimeoutMs`, test-seam shrinkable). A timeout emits
`TaskEvent.Failed("Tier-1 tool timed out after …ms")`, the "✗" channel message, and releases
the lock via the normal `finally` — so every subsequent task can start again.

**SkillExecutor** (C5 / FIX 5) bounds at STEP boundaries only — never mid-step:
- wall-clock bound (default 60 s, injectable) checked before each step → `SkillResult.timedOut`
- stop predicate (FIX 9) checked before each step → `SkillResult.cancelled`
`TaskBudget` stays EXACTLY in `DefaultAgentService.runAgentLoop` (once per LLM round) — it
cannot change value inside `SkillExecutor`/`RepeatActionsTool`, which make no LLM calls.

**Cancellation contract (FIX 9):** `cancelCurrentTask()` sets the session's `stopRequested`
flag (via `markStopping`); skill steps and the DirectTool pre-execution check read that flag.
A **cancelled** skill emits `TaskEvent.Cancelled` and **must NOT fall back to the agent loop**
(no respawned AI run). `RepeatActionsTool`'s pre-existing `MAX_TOTAL_STEPS` cap is pinned by a
test rather than re-deriving a token budget.

## 14. Revision history

- 2026-08-28 (P3.1): normalization/anchor/number unification, English-only strip, golden corpus.
- 2026-08-28 (P3.2): safety gates on Tier-1 DirectTool; §10.
- 2026-08-28 (P3.7): TaskShortcuts deleted; disposal recorded in §11.
- 2026-08-28 (P3.8): TaskClassifier deleted; disposal recorded in §12.
- 2026-08-28 (P2 C5/FIX5/FIX9/FIX10): shared `BoundedExecution` + skill step-bound checks +
  direct-tool wall-clock bound + skill cancellation contract; §13.
- 2026-08-28 (P2 FIX11a): global blocklist gate on DirectTool; §10.
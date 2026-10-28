# Repo questions — a decision inventory (not a recommendation list)

Date: 2026-10-25 · commit `7acea8c` (v3.5.1)

**How to read this.** Every item is an *observation* plus an *open question*. I am not
saying what you should do; I am listing places where the repo currently answers a
question implicitly, and asking whether that implicit answer is the one you want. Answer
only the ones you care about — a number is enough ("D12: yes", "D31: neither, actually…").

Ordering is by how much a decision would ripple, not by severity.

---

## A. What this product is (its own answer is currently split)

**D1.** The package is `com.returngift.agent`, the manifest label is `@string/app_name`,
but 101 source files say `Claw…` (`ClawApplication`, `ClawAccessibilityService`,
`ClawNotificationListener`) and 330 say `ReturnGift…`.
→ *Is "Claw" a legacy internal name kept for compatibility, or a second identity you still
use? Does anything user-visible ever say "Claw"?*

**D2.** `README` markets a personal agent; `AGENTS.md` describes a repo where engine
internals must stay app-agnostic; `docs/OPENHANDS_AGENT_TOOLS.md`, `AI_AGENT_PLAYBOOK.md`
and `skill-spec.md` read like specifications for a much larger product (skill taxonomy with
`owner: team-info`, `staleness_sla_days`, canary status).
→ *Which of these audiences is the real one: you alone, a small team, or external users?*

**D3.** There are three channel families (`discord/`, `telegram/`, `wechat/`), a LAN config
server (nanohttpd), a cloud deep-agent service, an external-automation entrypoint, and an
accessibility automation engine — in one APK.
→ *Are these all live product surfaces, or are some vestigial from an earlier scope? Which
ones did you personally use in the last month?*

**D4.** `app/src/main/java/.../channel/wechat/` is 1,782 lines and `AutoReplyManager.java`
is 1,215 lines.
→ *Is WeChat auto-reply still in scope? If not, is it kept because removing it risks
something you can't test?*

---

## B. How a task is described (four formats currently coexist)

**D5.** Four ways to express "how to do something" ship in the APK:
| Format | Count | Loaded by |
|---|---|---|
| `assets/skill_library/skills/*.yaml` | 19 | `SkillRegistry.loadYamlSkills` |
| `assets/playbooks/*.md` | 20 | `PlaybookManager.loadAll` |
| `assets/playbooks/ask-external-ai.yaml` | 1 | *(nothing — see D7)* |
| Kotlin `MicroStepCatalog` | 806 lines | `MicroStepPlanner` |
plus `SocialPostRoutine` (`profiles` rows) and the new `PlanComposer`.
→ *Which of these is the one you would edit today to teach the agent a new task?*

**D6.** 14 task names exist in **both** the skill YAML set and the playbook MD set
(`send-message`, `set-alarm`, `make-phone-call`, `open-conversation`, `search-repository`,
`record-audio`, `navigate-settings`, `view-assignments`, …). They are different files with
different content.
→ *When the two describe the same task differently, which one does the app actually
follow — and which one is the stale copy?*

**D7.** `assets/playbooks/ask-external-ai.yaml` is never read: `PlaybookManager` scans for
`*.md` only. Its header says *"MERGE INSTRUCTIONS: Replace the existing ask_external_ai
section in skill_definitions_v2.yaml"* — a file that does not exist in the repo.
→ *Is this file a scratchpad that slipped into `assets/`, or an intended source?*

**D8.** The new plan system (`PlanComposer` → `PlanStep`) and the older micro-step system
(`MicroStepCatalog` → `MicroStepModel`) both produce ordered UI steps, with different
models, both active (`AgentPreferences.enforceMicroSteps` defaults to **true**, and the plan
contract now gates `finish`).
→ *Which one owns the "what are the steps" question? Is the plan supposed to replace
micro-steps eventually, feed them, or stay a parallel layer?*

**D9.** Precedence when a task could match several engines — from `DefaultAgentService`:
`StructuredRoutineRegistry.match` (L754) → `PlaybookManager.match` (L785) → plan-first →
LLM loop.
→ *Is that order the intended one? What should happen when the routine matches but the
plan says something different?*

**D10.** `PlanContract.TOOL_ACTIONS` maps tool names to plan actions; `MicroStepCatalog`
describes steps in its own vocabulary; skills YAML carry `taxonomy.capability`
(`read`/`write`), `risk_tier`, `reversible`, `idempotent`.
→ *Should there be one vocabulary for "what kind of action is this" across all four, or are
they deliberately separate?*

**D11.** `docs/skill-spec.md` (265 lines) defines a skill schema with versioning, canary
status and an owner field.
→ *Are those governance fields (`version`, `status`, `owner`, `last_validated_utc`,
`staleness_sla_days`) actually consumed by code, or documentation-only metadata?*

**D12.** `app/src/main/assets/skills/` contains 1 file; `skill_library/skills/` contains 19.
→ *Is `assets/skills/` a leftover directory, or a second load path I missed?*

---

## C. The agent loop and its guards

**D13.** `DefaultAgentService.runAgentLoop` is ~2,084 lines and hosts ten decision systems
in one function body: intent gate, plan-first, plan contract, personal-content consent,
target-spec gate, allow-list, pre-action judge, action verification, watchdog, exec budget,
artifact contract, undo.
→ *Is the current order of those gates a considered design, or the order they were added?
Which pair, if swapped, would break first?*

**D14.** The plan contract I added last turn gates every tool call and `finish`. Several
older guards make overlapping judgements (`directDeviceDataGuard.maybeBlockFinish`,
`inAppSearchGuard`, `emailComposeGuard`, `artifactContract`, `microStepTracker`).
→ *Do you want one authority for "may this finish?", or a chain of vetoes that each know
their own domain? (The current code is a chain.)*

**D15.** Only 1 test file references `DefaultAgentService`, and 0 reference `ChatScreen`,
`TaskFlowController`, `ChatSessionController`, `LocalLlmClient` or `AutoReplyManager`.
→ *Is the loop deliberately untested because it needs a device, or is it untested because
it grew faster than the harness?*

**D16.** `Thread.sleep` appears 62 times in main sources.
→ *Of those, how many are on a background executor vs the main thread? Do you know, or
would you want a preflight rule to force them into an explicit wait helper?*

**D17.** 72 sites swallow exceptions (`catch (_: Exception)`, `runCatching{}.getOrNull()`).
In the loop, a dead accessibility service, a failed tap and a JSON error all become `null`
and are read as "retry".
→ *Would you rather see more failures surfaced as typed outcomes (and handled), or is
silent-degrade the deliberate product choice?*

**D18.** `AgentPreferences` has 5 user-visible booleans; `KVUtils` holds 41 keys; the new
strict-plan-mode key (`agent_strict_plan_mode`) has **no UI** and defaults to `false`.
→ *Should settings be a curated surface (few, safe) or a full control panel? What happens to
a preference that exists in code but nowhere in the UI?*

**D19.** Two orchestrators appear adjacent: `TaskOrchestrator` (860 lines) and
`ui/chat/TaskFlowController` (863 lines), plus `ChatSessionController` (831).
→ *Which one owns task lifecycle today? Are the other two delegating, or duplicating?*

---

## D. Consent, permission and safety semantics

**D20.** At least four consent mechanisms exist: `AllowListToolGate` (+ per-task
`taskConsentedSurfaces`), `PersonalContentConsentGuard` (Allow once / remember / cancel),
`AppAllowListGuard`/`AppAllowListStore`, `AgentPreferences.askBeforeNewApp`, plus the new
`McpToolPolicy` approvals and `PlanContract` approval steps.
→ *How many distinct "yes" gestures should a user see in one task? Is there a maximum you'd
enforce?*

**D21.** `ClarificationManager.request` is called from 10 places (loop, orchestrator,
pre-action judge, MCP proxy).
→ *Do all of them share one question-queue and one timeout, or can two prompts race for the
same chat surface?*

**D22.** Remembered grants persist per app (`PersonalContentConsentGuard.remember`,
`AppAllowListStore` in `returngift_allowlist.db`).
→ *Where does the user see and revoke them? Is "Settings → App permissions" the single
place, or can a grant exist with no UI to undo it?*

**D23.** The plan contract's approval step (`ASK_USER`) is separate from
`PreActionJudge`'s approval for risky verbs.
→ *Should an approval given to a plan step also satisfy the risky-verb judge (one prompt),
or must the user confirm twice (two different reasons)?*

**D24.** `microStepTracker.blockFinishReason()` (gating on steps) and the plan contract's
`completion()` both refuse `finish`.
→ *When both exist and disagree, whose verdict is final? Right now: whichever is checked
first, which is the older guard.*

---

## E. State and storage

**D25.** Six SQLite databases plus MMKV plus a markdown file tree:
`returngift.db` (chats), `returngift_allowlist.db`, `learned_procedures.db`,
`shared_knowledge.db`, `execution_tracker.db`, `correction*.db` (via `CorrectionDatabase`),
plus `AppGuideStore`, `TaskSessionStore`, `McpServerStore`, `McpPolicyStore`,
`McpSecretStore`, `PlanCheckpointStore`, `TaskCheckpointStore`, `ConversationStore`,
`AppLogStore`, and a vault of markdown files.
→ *Is "many small stores" a deliberate isolation choice, or accumulated? Is there a backup /
export / "reset my data" story that covers all of them?*

**D26.** Two checkpoint systems: `agent/checkpoint/TaskCheckpointStore` and the new
`agent/plan/PlanCheckpointStore`. Both key on a task and resume after interruption.
→ *Which one resumes what? Can a task resume from one and claim the other's progress?*

**D27.** Conversations are markdown files with SQLite as an index (`ChatDatabase` comment:
"Markdown files are source of truth; this DB is the index").
→ *What happens when they diverge (file edited, row stale)? Is there a repair path?*

**D28.** `AppGuideStore.storage` is a swappable seam (used by MCP secrets as fallback), and
`KVUtils` is the MMKV layer behind it.
→ *Do you want one storage abstraction with named namespaces, or is per-store storage
intentional so each can move independently?*

**D29.** Learned knowledge has three tiers (`learned > connector > built-in`) in
`AppKnowledge`.
→ *Can a learned row ever be wrong in a way the user can see and correct, or is the only
escape to clear data?*

---

## F. Exposure and security

**D30.** `ExternalAutomationReceiver` and `ReturnGiftAutomationActivity` are both
`exported="true"` with **no permission and no caller check**; the only gate is
`KVUtils.isExternalAutomationEnabled()` (default?).
→ *Should a third-party app be able to trigger tasks once you flip that switch? Would you
want a signature check, a token in the intent, or per-caller scoping?*

**D31.** The LAN `ConfigServer` (nanohttpd) verifies a bearer token from
`ServerTokenStore`; cleartext is allowed for `.local` / `.lan` / `.internal` domains by the
network-security config.
→ *Is the config server meant to be reachable from any device on the LAN, and is the token
generated/rotated with a UI the user understands?*

**D32.** Provider API keys live in plaintext MMKV (`KVUtils.getLlmApiKey` /
`setApiKeyForProvider`), while MCP tokens are now Keystore-encrypted.
→ *Is the asymmetry intentional (e.g. keys must be readable by a background service before
Keystore unlock), or just unfinished?*

**D33.** `AutoReplyManager` reads notifications (`ClawNotificationListener`), and
`channel/wechat` automates another app.
→ *What is the documented boundary of what the agent may read? Is it written down anywhere
the user sees?*

**D34.** `isMinifyEnabled = false` in release, with a `proguard-rules.pro` present but
unused.
→ *Is that to keep stack traces readable in bug reports, or because minifying broke
something once? (That reason would be worth writing down either way.)*

**D35.** 19 manifest permissions, several of them special-access
(accessibility, notification listener, overlay, usage stats, battery-optimisation).
→ *Which of these are hard requirements, and which exist for a feature you would drop if it
meant fewer permissions?*

**D36.** The app ships a universal APK with `RETURNGIFT_ABI` supported but unset.
→ *Is the single 141.9 MB APK the intended artifact (e.g. "works on any device"), or a
default that was never revisited?*

---

## G. Build, release and versioning

**D37.** No workflow runs on `pull_request`; `auto_build_and_test.yml` and `build.yml` are
`workflow_dispatch` only; `release.yml` triggers on tags and runs **no** unit tests (0
references to `testDebugUnitTest`).
→ *Was automatic gating removed for a reason (runner cost, flaky tests, quota), or never
added? This is the question that decides how the next structural change should be landed.*

**D38.** Version truth lives in three places: `app/build.gradle.kts` defaults
(`3.5.1`/`30501`), the tag (`v3.5.1` → derived at build time), and `README` changelog.
→ *Which is authoritative when they disagree? Should the build fail if the tag and the
gradle default disagree?*

**D39.** `local.properties` carries signing credentials in CI, and the fallback keystore is
generated per run (not stable across runs) when secrets are absent.
→ *Has a release ever been signed by the fallback key? What would happen to an in-app update
signed by the other one?*

**D40.** The release APK is built, checksummed and published, but nothing inspects the APK
afterwards (no `versionCode` read-back, no smoke install).
→ *Would you want the release job to assert the APK's own `versionName`/`versionCode` before
publishing?*

**D41.** `scripts/ci-preflight.sh` encodes past compile mistakes as greps; `build.yml` still
contains a step titled "Verify YamlSkillLoader.kt integrity" that greps for unclosed
comments.
→ *Is that step still needed, or is it a fossil of one bad merge?*

---

## H. Tests and verification

**D42.** 74 test files / 2,055 assertions, 73 pure JVM + 8 Robolectric.
→ *Do all of them pass under `./gradlew testDebugUnitTest` right now — and how would you
know, given no workflow runs them?*

**D43.** New modules (plan, MCP, routing) were verified here through a hand-built JVM harness
because there is no Android SDK in this environment.
→ *Do you want those suites wired into Gradle as first-class `test` sources (so CI runs
them), or is the current ad-hoc harness enough for you?*

**D44.** `qa`-style documents: `QA_CHECKLIST.md` has 422 checkbox items across 4,539 lines,
mostly `[ADB]` device checks.
→ *Is that list maintained and executed, or has it become a historical log? Roughly what
fraction of items have a recorded PASS?*

**D45.** Robolectric appears in 8 tests; the rest of the Android-touching code is untested.
→ *Where is the line: pure logic in JVM tests, device behaviour in manual QA? Or would you
want more Robolectric coverage of the UI/loop?*

---

## I. Documentation and governance

**D46.** 52 markdown files; the largest governance docs are `QA_CHECKLIST.md` (4,539),
`AGENTS.md` (465+), `docs/OPENHANDS_AGENT_TOOLS.md` (407), `docs/MCP_AND_MODEL_ROUTING.md`
(326), `docs/ARCHITECTURE_DECISIONS.md` (281).
→ *Which document do you actually consult before changing code? Which one has drifted far
enough that you'd rather archive it than fix it?*

**D47.** `AGENTS.md` holds 14 rules from my last change, plus rules from ~15 earlier packs,
each with a date and a story ("Pitfall 7 (release v3.0.9)").
→ *Should `AGENTS.md` be a short invariant list (with history moved to a changelog), or is
the archaeology valuable to you?*

**D48.** `docs/ARCHITECTURE_DECISIONS.md` exists but is not referenced from `AGENTS.md`.
→ *Does it still reflect reality? Is an ADR expected for a change like the plan contract?*

**D49.** Comments in code carry the same narrative style as the docs ("The incident this
fixes: …", "Pitfall 6 (broke v2.3.0…)").
→ *Do you want that style preserved in new code (it is unusual and informative), or trimmed
as the codebase grows?*

---

## J. Structural questions (the ones that would mean "re-create")

**D50.** `ChatScreen.kt` (3,791 lines, 62 composables) + `ComposeChatActivity` +
`TaskFlowController` + `ChatSessionController` + `TaskOrchestrator` ≈ 9k lines on the chat
surface.
→ *Would you split the chat surface by feature (message list / composer / task panel /
attachments) even if it means touching many files, or keep the monolith because it is
locally understood?*

**D51.** The loop's guards are classes but invoked by name inside one function; the order is
implicit (line position).
→ *Would you want the guard chain to become an explicit ordered list (`List<LoopGuard>`)
that can be enumerated, tested and reordered in one place?*

**D52.** `features` are wired in `ClawApplication.onCreate` (~40 lines of static
initialisation + background threads).
→ *Is startup order an implicit contract anywhere (e.g. "MMKV before any store read")? Would
you want it declared as an explicit sequence with a test?*

**D53.** Two local-model paths (`LocalLlmClient`, `LocalModelManager`, `EngineHolder`,
`LocalBackendHealth`) and one cloud path with routing (`RoutedLlmClient`).
→ *Is the local path meant to reach feature parity (planning, contract, routing decisions),
or is it a degraded mode by design?*

**D54.** `SkillTelemetry`, `ExecutionTracker`, `LearnedProcedureStore`, `CorrectionDatabase`
all learn from runs.
→ *Would you want one "what did the agent learn from this task" record, or are these
independent by design?*

**D55.** There is no module boundary (`app` is one Gradle module); pure layers (`plan`,
`mcp`, `routing`) are conventions, not enforced by the compiler.
→ *Would splitting those three into Gradle modules (so "pure" is enforced and tests stay
JVM-only) be worth the build complexity for you?*

---

## K. Product semantics (things only you can answer)

**D56.** When the plan and the real screen disagree, `GUARDED` mode lets the agent proceed
and records the deviation; `STRICT` refuses.
→ *Which should be the default on your own device? Is the strict flag something you'd expose
in Settings or keep as a developer switch?*

**D57.** On resume, the plan contract continues from the saved step (30-minute TTL).
→ *Should a resumed task tell you it resumed, ask you first, or continue silently?*

**D58.** `McpToolPolicy` asks before every destructive remote call, every time.
→ *Is "ask every time" acceptable for a server you trust, or would you want a per-tool
"never ask again, but log it" option?*

**D59.** Learned guides require three successful interactions before being stored.
→ *Is three the right threshold from your experience? Has a wrong guide ever been learned?*

**D60.** The agent fails over across providers on 429/quota/5xx, up to 3 attempts.
→ *Is silent provider failover acceptable, or should the user see "answered by X because Y
was rate-limited" in the transcript?*

---

## What I would need from you to go further

To answer D43, D42 and D37 properly I would need to actually run Gradle — impossible here
(no Android SDK). To answer D16, D18, D20 I would need one real task session's logcat. To
answer D3, D4, D31, D35 I would need to know which features you used last week.

The fastest path to good answers is probably: you mark the ~10 decisions that matter most,
and I turn those into either a restructure plan or a set of small, reversible changes —
your call, not mine.

# 1. OBJECTIVE

Perform a full audit of every file in the ReturnGift repo, then improve speed, accuracy, and repository hygiene by **wiring every useful-but-disconnected asset into the runtime** and only stripping what is verifiably dead. Deletion is a last resort requiring a four-signal gate (see §4 Phase 0); the user's directive is to implement, not blindly delete.

# 2. CONTEXT SUMMARY

ReturnGift is an on-device Android agent (LiteRT-LM + LangChain4j + Compose). Findings from the audit performed during planning:

**Proven-alive (hard-link evidence):**
- `widget/*` — referenced by-name from XML layouts (`activity_settings.xml` etc.); all 12 files survive.
- `service/KeepAliveJobService`, `service/BootReceiver` — declared in main `AndroidManifest.xml`.
- `debug/TaskTriggerReceiver`, `debug/DebugTaskReceiver` — declared in `src/debug/AndroidManifest.xml` (debug-only by design; comment explains why).
- All manifest-declared activities/services.

**Dual-location skill sources (bug-prone duplication):** `skill_library/skills/*.yaml` (19 files, repo root) is duplicated into `app/src/main/assets/skill_library/skills/` via `scripts/update-skill-registry.sh`. Root copy is source-of-truth for the Python lifecycle; the asset copy is what APKs ship — drift risk.

**Strong-signal dead / disconnected (each must pass the Phase-0 gate before action, because the grep-based index used here produced false negatives — e.g. it initially missed manifest-declared receivers):**
- Root `fixtures/screen_*.xml` (24 files) + `fixtures/README.md` — **but** `app/src/androidTest/` also has a fixtures area the instrumented tests may use; only the root copy is suspect. Check `WebFetcherTest`'s DDG fixture path too.
- `golden_transcripts/interrupt_handling.jsonl` — single eval file, no caller found.
- `prototype/` (11 HTML dashboard mockups) — design history.
- `demo/*.gif` — orphaned demos (README doesn't embed them).
- `docs/` website HTML files (GitHub Pages site) — keep ONLY if Pages is enabled; otherwise decide.
- `source_repo/` — empty directory.
- `core/` package (16 files: terminal, telemetry×3, input×3, vision, game, accessibility×5 incl. `ScreenTreeTokenOptimizer`/`SemanticNodeFlattener`) — zero references found; AGENTS.md's token-optimization behavioral description doesn't actually implement these.
- `server/` package (4 files: `ConfigServer`, `ConfigServerManager`, `CloudDeepAgentService`, `CloudDeepAgentManager`) — zero references found; appears superseded by `dev/GitHubCodeEngine`.
- Root-level doc overlap: `AI_INDEX.md` vs `docs/AI_INDEX.md`; `STRATEGY.md`/`EXECUTION_PLAN.md`/`EXPERT_REVIEW_TASKS.md`/`Expert_tasks.md`/`ARCHITECTURE_RECONSTRUCTION.md` — overlapping planning docs.
- Root `lint-baseline.xml` duplicates `app/lint-baseline.xml`.
- `skill_definitions_v2.yaml` + `skill_integration_analysis.md` — stale design snapshots.

**Optimization targets (already-known gaps, per AGENTS.md context):**
- `LocalLlmClient.chatStreaming` delegates to blocking → typing-indicator gap for the local model.
- Screen-tree token optimization advertised but not implemented in the runtime path.

# 3. APPROACH OVERVIEW

Five-phase approach with an explicit **verification gate** before any removal. Why: the audit tooling (grep index) produced false negatives during planning (it missed manifest-declared receivers until the manifest was viewed directly), so trust-but-verify is built into the workflow itself. Implement-first ordering maximizes value recovery, matching the user's directive.

- **Phase 0 — Verification gate:** a checklist procedure (exact-name `git grep` + direct manifest/file view) each deletion candidate must pass.
- **Phase 1 — Implement the disconnected code:** wire `core/` and `server/` packages into the runtime where genuinely useful; consolidate root fixtures into `androidTest` if they fit; discard only what fails all evaluations.
- **Phase 2 — Fix the known speed/accuracy gaps:** local-model streaming seam; screen-tree token optimization; skill-source drift guard.
- **Phase 3 — Consolidate duplication & orphaned assets:** doc merges with pointer files (no silent removal), demo/prototype/website decisions.
- **Phase 4 — Guardrails:** extend `scripts/ci-preflight.sh` and AGENTS.md so the cleaned state is durable.

# 4. IMPLEMENTATION STEPS

## Phase 0 — Verification gate (MUST run before any candidate removal)

For every candidate marked relocate/discard: re-verify using `git grep -n "<ExactClassName>"` (whole-tree literal search) plus a direct view of `AndroidManifest.xml` and the candidate's own header comment. Proceed only when **all four** signals agree:
1. No literal name match in `*.kt`/`*.java`/`*.xml`/`*.kts`.
2. Not declared in `main/AndroidManifest.xml` and not loaded via reflection/`Class.forName`.
3. For documentation merges: content is either duplicated or an explicit pointer file replaces it.
4. For fixtures: confirmed not referenced from any `androidTest`/`test` source (root `fixtures/` vs instrumented fixtures disambiguated).

**Goal:** eliminate false-negative risk like the manifest-receiver miss found during planning. **Method:** the gate is a written checklist the executing agent applies serially per file. **Reference:** this PLAN.md, executed against each candidate list below.

## Phase 1 — Implement disconnected code (implement-first)

**Step 1.1: Triage `core/` package (16 files).**
- **Goal:** recover genuinely useful implementations; discard only true dead code.
- **Method:** open each file, read its purpose. Prime candidates for wiring: `core/accessibility/ScreenTreeTokenOptimizer` + `SemanticNodeFlattener` (wire into the `getScreenTree()` path if functional — AGENTS.md already advertises this behavior). `core/input/*` (`TouchInputLayer`/`DynamicIMEInjector`/`DirectActionDispatcher`) — check whether they supersede or complement `InputTextTool` paths. `core/vision`, `core/game`, `core/terminal`, `core/telemetry` — evaluate individually; discard only after the Phase-0 gate.
- **Reference:** `app/src/main/java/com/returngift/agent/core/`, `service/ClawAccessibilityService.java`, `tool/impl/InputTextTool.java`.

**Step 1.2: Triage `server/` package (4 files).**
- **Goal:** decide whether the embedded config server is a live feature path or superseded by `dev/GitHubCodeEngine`.
- **Method:** read the four files; if functional and valuable, expose behind a Developer-settings toggle; otherwise discard after gate.
- **Reference:** `server/ConfigServer*.kt`, `dev/GitHubCodeEngine.kt`, `ui/settings/SettingsActivity.kt`.

**Step 1.3: Resolve root `fixtures/screen_*.xml`.**
- **Goal:** consolidate into the test source-set fixtures (where instrumented tests live) or confirm the root copy is redundant and discard after gate.
- **Method:** compare root `fixtures/` against any fixtures used by `app/src/androidTest/` and `app/src/test/`; check whether `WebFetcherTest`'s captured DDG response fixture lives here (AGENTS.md says it was "validated against a REAL captured DDG response").
- **Reference:** root `fixtures/`, `app/src/androidTest/`, `app/src/test/java/com/returngift/agent/agent/retrieve/WebFetcherTest.kt`.

**Step 1.4: Evaluate `golden_transcripts/interrupt_handling.jsonl`.**
- **Goal:** wire the eval transcript into the skill evaluator's expected input, or document it as a sample; never wire blindly.
- **Method:** check `skill_library/evaluator/skill_evaluator.py` input format and connect or discard after gate.
- **Reference:** `golden_transcripts/`, `skill_library/evaluator/skill_evaluator.py`.

## Phase 2 — Speed & accuracy fixes (from the project's own documented backlog)

**Step 2.1: Local-model chat streaming.**
- **Goal:** true streaming for the local model, eliminating the frozen typing indicator.
- **Method:** replace `LocalLlmClient.chatStreaming`'s blocking delegation with LiteRT-LM streaming if the SDK exposes it; otherwise keep delegation but document the seam honestly. Align with `ChatSessionController.sendChat` cloud path that already handles partials via `replaceTypingIndicator`.
- **Reference:** `agent/llm/LocalLlmClient.kt`, `ui/chat/ChatSessionController.kt`, `agent/llm/LocalModelRuntime`.

**Step 2.2: Screen-tree token optimization (depends on Step 1.1 outcome).**
- **Goal:** reduce per-read token cost in the agent loop (fewer tokens = faster + cheaper + fewer stalls).
- **Method:** if `ScreenTreeTokenOptimizer`/`SemanticNodeFlattener` survive Phase 1.1, wire them into `getScreenTree()`; if discarded as non-functional, implement a minimal inline flattener (collapse layout-only containers, drop invisible nodes) sized to the loop's actual need.
- **Reference:** `agent/exec/ScreenReadGate.kt`, `service/ClawAccessibilityService.java`, `core/accessibility/`.

**Step 2.3: Skill-source duplication guard.**
- **Goal:** prevent stale-asset drift between root `skill_library/skills/` and bundled `assets/skill_library/skills/`.
- **Method:** add a diff check to `scripts/ci-preflight.sh` comparing the two trees; keep `scripts/update-skill-registry.sh` as the sync tool.
- **Reference:** `scripts/ci-preflight.sh`, `scripts/update-skill-registry.sh`, `skill_library/skills/`, `app/src/main/assets/skill_library/skills/`.

## Phase 3 — Consolidate duplication & orphaned assets (gate-checked)

**Step 3.1: Documentation dedupe with pointers.**
- **Goal:** one canonical file per topic; discarded copies replaced by a one-line pointer to the canonical file (no silent removal).
- **Method:** choose canonical per topic — root `AI_INDEX.md` vs `docs/AI_INDEX.md` (recommend `docs/` canonical, root becomes pointer; also update CLAUDE.md's file table). Merge `EXPERT_REVIEW_TASKS.md` + `Expert_tasks.md` (keep the more recent; pointer the other). Read `STRATEGY.md`/`EXECUTION_PLAN.md`/`ARCHITECTURE_RECONSTRUCTION.md` for overlap and consolidate. Remove root `lint-baseline.xml` duplicate (keep `app/lint-baseline.xml`). Decide `skill_definitions_v2.yaml` + `skill_integration_analysis.md` (merge into `docs/skill-spec.md` or discard after gate).
- **Reference:** named root files, `docs/`, `CLAUDE.md`.

**Step 3.2: Decide `docs/` website, `prototype/`, `demo/`.**
- **Goal:** keep only what the project actually publishes or uses.
- **Method:** docs website — keep ONLY if GitHub Pages is active for this repo; otherwise consolidate or discard after gate. `prototype/` — if kept for design history, move under `docs/prototype/`. `demo/*.gif` — either embed in README where they add value or discard after gate.
- **Reference:** `docs/`, `prototype/`, `demo/`, `README.md`.

**Step 3.3: Empty `source_repo/`.**
- **Goal:** remove the empty directory, or note its intended purpose if it's a checkout target for scripts.
- **Method:** grep scripts for the `source_repo` literal first.
- **Reference:** `source_repo/`, `scripts/`.

## Phase 4 — Guardrails so it stays clean

**Step 4.1: Extend `scripts/ci-preflight.sh`.**
- **Goal:** fail CI when a new duplicate-of-record or stale skill asset appears.
- **Method:** add the skill-tree diff check (Step 2.3) and, if applicable, a `source_repo/` non-empty guard.
- **Reference:** `scripts/ci-preflight.sh`.

**Step 4.2: Update AGENTS.md memory.**
- **Goal:** record decisions made (what was implemented where, what was discarded and why) so future sessions don't re-audit.
- **Method:** append a dated audit section (pattern matches existing "EX pack"/"FX fix pack" sections).
- **Reference:** `AGENTS.md`.

# 5. TESTING AND VALIDATION

- **Phase-0 gate evidence:** for every candidate file acted on, the 4-signal checklist result is recorded in the QA notes; no deletions without the gate.
- **Unit tests (no SDK in this sandbox → CI or device only):** `./gradlew testDebugUnitTest` must stay green; `SkillRegistryTest`, `WebFetcherTest`, `SkillExecutorTest`, `ScreenReadGateTest`, `SelectorChainTest` are the sensitive suites for Phase 1/2 changes.
- **CI preflight:** `bash scripts/ci-preflight.sh` passes with the new checks added (Steps 2.3, 4.1).
- **Streaming fix (2.1):** local-model chat shows progressive tokens (no frozen typing indicator); cloud path in `ChatSessionController` regression-free.
- **Token optimizer (2.2):** if implemented, `get_screen_info` payload size drops measurably in logcat; loop behavior unchanged (`ScreenReadGateTest`/`SelectorChainTest` still pass).
- **Fixtures (1.3):** instrumented tests still find their fixtures after any move; `WebFetcherTest`'s DDG fixture path still resolves.
- **QA checklist update:** per repo rules, new entries added to `QA_CHECKLIST.md` for each implemented behavior (streaming, optimizer, server toggle if kept).
- **Final hygiene check:** `git status` shows only intended moves/edits; all decisions recorded in AGENTS.md.

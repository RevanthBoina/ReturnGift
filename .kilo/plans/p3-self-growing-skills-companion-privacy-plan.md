# P3 Phases Plan — Self-Growing Skills, Companion Body, Privacy Dashboard

## Goal
Complete P3 by implementing self-growing skill book (P3.1), second device body (P3.2), and privacy dashboard (P3.4), building on completed P3.3 (observation provenance). P3.3 must complete first — P3.1 and P3.4 depend on provenance data.

## Constraints
- Must comply with ReturnGift spine rules (AGENTS.md): lazy-first, on-device privacy (no raw content in provenance fields), atomic commits, golden corpus untouched, no new network dependencies, no auto-merge of draft skills.
- All provenance origin strings are closed-vocab-ish machine IDs only (packages, hostnames).
- No raw utterance/screen content in any provenance field.
- New Python scripts go in `skill_library/optimizer/` — they are offline lifecycle tools, not shipped APK components.

## P3.1 — Self-Growing Skill Book (trajectories → draft YAML → human approval)

### P3.1a — Export Trajectories from DebugReportManager
**Context**: DebugReportManager currently builds a debug ZIP with summary.txt and app logs. Need to add trajectory JSONL exports.
- **Task**: Modify `DebugReportManager.kt` to include the last N trajectory JSONL files and a provenance summary (P3.3 tag counts, not raw content).
- **What to add**:
  - Export each stored trajectory as `{taskId}.jsonl` in the debug ZIP
  - JSONL format: one line per event, fields: `taskId`, `stepIndex`, `eventType`, `timestamp`, `screenHash`, `screenSummary`, `actionTool`, `actionParams`, `resultSuccess`, `resultSummary`, `latencyMs`, `appPackage`, `source` (P3.3 provenance tag)
  - Add provenance summary section: count of observations per provenance kind (SCREEN/WEB/EXTERNAL_AI/USER/SYSTEM), total provenance-tagged observations
  - Human-initiated only — the report flow already is, no code changes needed to trigger it
- **Dependencies**: ExecutionTracker already exports `exportTrajectoryToJson(taskId)` and `getRecentTrajectories(limit)`. The JSONL format is derived from the existing Trajectory/ExecutionEvent data classes.
- **Risk**: Low — JSONL reading already exists in skill_library code paths.

### P3.1b — Distill Repeated Trajectories into DRAFT YAML
**Context**: Python `skill_optimizer.py` currently only demotes failed routes and boosts alternatives. Need to add trajectory distillation logic.
- **Task**: Extend `skill_library/optimizer/skill_distiller.py` (or `skill_optimizer.py`) to:
  - Input: trajectory JSONL files (from P3.1a export)
  - Detect repeated successful sequences: same app package, ≥2 occurrences, ≥3 steps, same selector-chain SHAPE (exact values abstracted into slots; varying → slot with `prompt_if_missing`)
  - Execute: emit a DRAFT yaml conforming to schema v2.1
    - `status: experimental`
    - `taxonomy.risk_tier = max risk of contained tools` (NEVER lower)
    - `execution.route` from observed selector chain
    - `recovery` from observed retries
    - `provenance: {trajectories: <hashes>, generated: <date>}` block (P3.3 tags, no raw content)
  - Critical security gate: Reject any candidate whose slots/params contain instruction-shaped text. Must run P1.2's canary substring check in Python against the draft's string fields before writing.
  - Output: Write draft yaml to `skill_library/skills/` with `status: experimental`, NOT auto-merged — human PR is the gate.
- **Dependencies**: 
  - ExecutionTracker JSONL export (P3.1a)
  - Existing skill_optimizer.py refinement infrastructure
  - SkillRegistry reads whatever is in `assets/skill_library/skills/` (no new app code needed for P3.1d)
- **Risk**: Medium — must ensure canary check catches instruction-shaped text; must not corrupt existing skills.

### P3.1c — Skill Lifecycle Promotion Logic
**Context**: skill_lifecycle.py currently has `CONFIDENCE_THRESHOLD = 0.75` and promotes after evaluation. Need to add staleness SLA and experimental→validated promotion gate.
- **Task**: Extend `skill_library/lifecycle/skill_lifecycle.py`:
  - Add `STALENESS_SLA_DAYS = 30` (or configurable) — skills not validated within SLA are auto-rejected
  - Add `validated_run_count` tracking in `ExecutionContext` — count successful on-device runs reported via `SkillTelemetry` events
  - Change promotion gate: experimental → validated ONLY after N=3 successful FixtureValidator runs OR 3 successful on-device runs reported back via SkillTelemetry
  - Add staleness check: if `last_validated_at + STALENESS_SLA_DAYS < now`, mark REJECTED
  - Keep existing: refined skill update only after verified re-evaluation
- **Dependencies**: SkillTelemetry events already exist (agent/skill/SkillTelemetry.kt)
- **Risk**: Low — adds tracking + SLA logic, no changes to core promotion flow.

### P3.1d — Ship Drafts via Existing Registry Update
**Context**: Skills ship via `scripts/update-skill-registry.sh` + ci-preflight skill-assets-in-sync check.
- **Task**: Ensure the ship path works for draft skills
  - `scripts/update-skill-registry.sh` already reads `skill_library/skills/*.yaml` and updates the in-repo registry
  - No new app code needed — YamlSkillLoader already reads whatever is in `assets/skill_library/skills/`
  - The ci-preflight.sh `skill-assets-in-sync` check already validates `skill_library/skills/` vs `app/src/main/assets/skill_library/skills/`
  - Draft yaml files ship with `status: experimental` and `risk_tier` set appropriately — human PR approval gate is enforced by the existing repo ruleset (require 1 approving review + passing checks)
- **Dependencies**: Existing ci-preflight.sh, repo rulesets, update-skill-registry.sh
- **Risk**: Low — existing infrastructure, just needs draft yaml to be placed correctly

## P3.2 — Second Body: Companion Device Management

### P3.2a — DeviceRegistry: In-Memory Companion Device Registry
- **Task**: Create `core/DeviceRegistry.kt` (or equivalent in `agent/skill/`) as an in-memory registry of companion devices
  - Fields: `id`, `name`, `address`, `deviceType`, `fingerprint-of-capabilities`
  - Discovery via mDNS over existing LAN infrastructure (the ConfigServer's nanohttpd + P0.2 pairing token — companion presents SAME pairing token; no new auth surface)
  - Thread-safe singleton, observable via `onDeviceChange` callback
- **Dependencies**: ConfigServer already present; P0.2 pairing token infrastructure already exists
- **Risk**: Low — registry is in-memory, discovery is mDNS over existing LAN

### P3.2b — ToolRegistry Dispatch Stub: `executeOn(deviceId, toolName, params): ToolResult`
- **Task**: Extend `ToolRegistry.kt`:
  - Add `executeOn(deviceId: String, toolName: String, params: Map<String, Any>): ToolResult` method
  - This method POSTs to the companion's minimal "hands" endpoint (documented JSON contract, token-gated)
  - The companion target itself (TV-optimized build of this app exposing ONLY the accessibility service + hands endpoint, no UI, no brain) is SCOPE FOR A LATER WAVE — this wave ships the phone side + the contract doc
  - Add loopback E2E test: register a fake companion (test server in the unit test), dispatch `dpad_up`, assert the JSON arrives token-gated
- **Dependencies**: ToolRegistry already exists; existing TV tools (dpad, menu, power, volume) provide the command vocabulary
- **Risk**: Medium — must ensure token-gating works; must not expose phone accessibility to untrusted companions

### P3.2c — Companion Device Contract Document
- **Task**: Create `docs/specs/companion-device-contract.md` documenting:
  - JSON contract for "hands" endpoint: `{ "tool": "dpad_up", "params": {}, "token": "<pairing-token>" }`
  - Device discovery via mDNS
  - Pairing token reuse (same token on phone + companion, not per-device secrets)
  - Capability fingerprint format
  - Token revocation
- **Dependencies**: None — pure docs
- **Risk**: Low — documentation only

## P3.4 — Privacy Dashboard (the claim becomes a UI)

### P3.4a — Cloud LEAVE Event Ledger in ExecutionTracker
**Context**: All data already exists per-ag; need to make it visible.
- **Task**: 
  - Add `LEAVE` event type to `ExecutionTracker.EventType` enum (alongside TASK_START, OBSERVE, etc.)
  - Add `source` column tracking for LEAVE events (already there from P3.3 schema v3)
  - Extend `LlmClientFactory` to write a `LEAVE` event at the ONE site: `agent/llm/LlmClientFactory.kt` — the response path after all providers. This is the single choke point where ALL cloud calls write a LEAVE row.
  - Local-only runs write ZERO lines (assert this in a test: "the absence of rows IS the privacy proof")
  - LEAVE event fields: `provider`, `model`, `inputTokens`, `outputTokens`, `ts` (already matched to existing schema)
- **Dependencies**: ExecutionTracker already has source column (P3.3); LlmClientFactory is the single choke point
- **Risk**: Low — adds one site (LlmClientFactory) to write LEAVE events; existing schema handles it

### P3.4b — Safety Counters Section in Settings → Privacy
- **Task**: Extend the existing `SettingsActivity.kt` privacy group (do NOT build a new activity — lazy-first per AGENTS.md) with a fourth read-only section:
  - `(4) Safety counters: canary hits, judge blocks (P2.5), blocklist hits, FP counters` — closed vocab only
  - Pull counters from existing telemetry/KV stores
  - Display: "Canary hits: N", "Judge blocks: N", "Blocklist hits: N", "FP (false positive) counter: N"
- **Dependencies**: PersonalContentConsentGuard already lists remembered apps in Settings → Privacy per AGENTS.md; Tier1Telemetry counters already exist; P2.5 judge blocks already tracked
- **Risk**: Low — reuses existing privacy group, just adds read-only counter display

### P3.4c — Manifest-Drift Test for Permissions-Justification.md
- **Task**: Create a test that parses `docs/permissions-justification.md` markdown table and fails on manifest drift (four-signal discipline: literal grep kt/java/xml/kts + manifest view + doc-pointer rule + fixture disambiguation)
  - The test should: read the markdown table of permissions + justifications, grep the AndroidManifest.xml for each permission, fail if a manifest-registered permission has NO corresponding justification row OR if a justification row references a permission NOT in the manifest
  - Enforce the same four-signal gate used by `scripts/ci-preflight.sh`
- **Dependencies**: `docs/permissions-justification.md` already exists (from P1.4c); AndroidManifest.xml already present
- **Risk**: Medium — test must correctly distinguish new permissions from framework ones; must not produce false positives on framework API additions

### P3.4d — Zero-Row Test for Local-Model Task LEAVE Event Validation
- **Task**: Add a unit test that asserts: when the active model is LOCAL, ExecutionTracker contains ZERO `LEAVE` event rows for that task
  - This is the privacy proof: "the absence of rows IS the privacy proof"
  - Test should: launch a local-model task, verify no LEAVE rows are written, pass if count = 0
- **Dependencies**: ExecutionTracker LEAVE event writing (P3.4a); local model inference path already exists via LocalLlmClient
- **Risk**: Low — asserts existing behavior (local model should not write LEAVE rows)

## Finalization
- Update README Features section with two new lines:
  - `Self-growing skills [human-approved]` — P3.1 draft → PR gate
  - `Privacy dashboard` — P3.4 four-section dashboard
  - Per P0.3's discipline: add lines only after code is in place
- CI green; corpus untouched

## Success Criteria (Definition of DONE - P3)
1. Provenance: every tracker observation carries a tag (test); forgetApp(package) removes exactly the tagged rows + cache + session state (test); Vault shows tags.
2. Skill genome: a synthetic 3×-repeated trajectory JSONL produces a schema-valid DRAFT yaml with status: experimental, risk_tier = max of contained tools, instruction-shaped slots REJECTED by the canary check (test all three); lifecycle promotes only after 3 validated runs; ship path = existing update-skill-registry.sh (no new app code).
3. Second body: companion contract doc + loopback E2E (token-gated dispatch) green.
4. Privacy dashboard: all four sections render; the "zero LEAVE events for a local-model task" test passes; manifest-drift test fails correctly when a permission is added without the doc row.
5. CI green; README gains two lines (self-growing skills [human-approved], privacy dashboard) — with code, per P0.3's discipline.

## DO-NOT
- No auto-merge of draft skills (human PR is the gate)
- No cloud in any P3 path
- No companion APK build this wave (contract + phone side only)
- No raw content in any provenance field, counter, or ledger line — closed vocab and machine ids only
# Expert Tasks — Implementation Plan

## What exists
- `SkillRegistry` / `SkillExecutor` / `BuiltInSkills` — 9 hardcoded Kotlin skills
- `DefaultAgentService` — full LiteRT-LM + cloud LLM agent loop (working)
- `LocalLlmClient` / `LocalModelRuntime` / `EngineHolder` — LiteRT-LM integration (working)
- `ConfirmDialog` / `ConfirmDialog.showWarm` — confirmation UI (working)
- `XLog` — logging (no PII filtering)
- `PipelineRouter` — regex Tier-1.5 skill matching (no embedding, no anti-trigger)
- `.github/workflows/build.yml` — debug APK CI (exists, no tests/lint)
- `app/src/test` / `app/src/androidTest` — empty

---

## Tasks

### 1 · YAML → Runtime Bridge  *(Critical)*
**Files to create:**
- `skill/YamlSkillLoader.kt` — SnakeYAML parser: reads `assets/skill_library/skills/*.yaml` → `YamlSkill` data model
- `skill/YamlSkill.kt` — data classes mirroring YAML schema (slots, routes, safety, routing, recovery)
- `skill/YamlSkillCompiler.kt` — transforms `YamlSkill.execution.routes` → `Skill` / `SkillStep` objects the existing `SkillExecutor` can run
- `skill/SelectorResolver.kt` — resolves `sel.{app}_send_button` style references against live `AccessibilityNodeInfo` trees
- `skill/RecoveryExecutor.kt` — runs `recovery:` blocks (selector_not_found → rescan/scroll/next_route; auth_wall → abort; app_not_installed → delegate)

**Wire-up:** `SkillRegistry.loadBuiltInSkills()` → also call `YamlSkillLoader.loadAll(context)` and register compiled skills.

**Manual step required:** Copy `skill_library/skills/*.yaml` into `app/src/main/assets/skill_library/skills/`. SnakeYAML must be added to `libs.versions.toml`.

---

### 2 · Model Inference Layer  *(Critical — verify only)*
`LocalLlmClient`, `LocalModelRuntime`, `EngineHolder` are **already implemented** with LiteRT-LM 0.10.0.  
`TokenMonitor` callback exists and is wired into `DefaultAgentService`.  
LoRA hot-swapping and SGMV are **not implemented** — no API surface exists in LiteRT-LM 0.10.0 for this.

**Files to create:**
- `agent/llm/ModelHealthCheck.kt` — startup check: verifies model file exists at configured path, logs backend (GPU/CPU), emits a `TokenMonitor` baseline ping.

**Manual step required:** Place compiled `.litertlm` model file on device and set path in Settings. LoRA/SGMV requires a future LiteRT-LM SDK version — defer.

---

### 3 · Safety & Confirmation Gates  *(Critical)*
**Files to create:**
- `agent/SafetyInterceptor.kt` — before every tool execution: (a) looks up active skill's `safety` block, (b) checks `blocklist_patterns` regex against tool params, (c) for `risk_tier ≥ 2` shows `ConfirmDialog.showWarm` and suspends execution, (d) enforces `never_retry_after` checkpoints via a per-session set.
- `agent/PiiFilter.kt` — wraps `XLog` calls: redacts fields listed in `redact_in_logs` before writing.

**Wire-up:** `DefaultAgentService.runAgentLoop` — call `SafetyInterceptor.check()` before `ToolRegistry.executeTool()`. Replace direct `XLog` calls for tool params with `PiiFilter.redact()`.

---

### 4 · Screen Capture & Fixture Pipeline  *(High)*
**Files to create:**
- `agent/ScreenFixturePipeline.kt` — on every screen transition (via `ClawAccessibilityService` window-change event): dumps accessibility tree XML, hashes it, writes to `fixtures/screen_<pkg>_<hash>.xml` in app files dir.
- `agent/FixtureValidator.kt` — given a `screen_signature` block from YAML, checks `package_equals` + `require_all/require_any/require_none` node IDs against the current live tree.
- `agent/StalenessChecker.kt` — reads `staleness_sla_days` from loaded `YamlSkill`, compares against `last_validated_utc`, logs a warning if stale.

---

### 5 · Deterministic Routing Accuracy  *(High)*
**Files to modify:**
- `PipelineRouter.kt` — add anti-trigger enforcement: after a skill trigger matches, check `anti_triggers` list; if the task matches an anti-trigger pattern, redirect to the specified skill instead.
- `SkillRegistry.kt` — `findByTrigger` returns ranked list; caller picks highest-priority match (use `routing.priority` from YAML).

**Files to create:**
- `agent/EntityExtractor.kt` — simple regex/slot extraction for `required_entities` and `optional_entities` from YAML routing block; returns `Map<String, String>` of extracted values.

---

### 6 · Telemetry & Observability  *(High)*
**Files to create:**
- `agent/TelemetryLogger.kt` — SQLite-backed event log: `INSERT` one row per skill execution with `route_used`, `step_latency_ms`, `outcome`, `selector_hit_rank`. Exposes `query(limit)` for the self-correction loop.
- Wire `TelemetryLogger.log()` into `SkillExecutor.execute()` and `DefaultAgentService.runAgentLoop()`.

---

### 7 · Testing Infrastructure  *(High)*
**Files to create:**
- `app/src/test/.../PipelineRouterTest.kt` — unit tests: trigger matching, anti-trigger rejection, compound-task bypass.
- `app/src/test/.../SkillExecutorTest.kt` — unit tests: param resolution, optional step skip, retry logic, fallback goal generation.
- `app/src/test/.../YamlSkillLoaderTest.kt` — unit tests: parse `send_message.yaml`, verify slots/safety/routing fields.
- `app/src/androidTest/.../SafetyInterceptorTest.kt` — instrumented: blocklist regex match, tier-2 confirmation gate.

---

### 8 · Build & CI/CD  *(High)*
**Files to create/modify:**
- `.github/workflows/build.yml` — add: unit test step (`./gradlew test`), lint step (`./gradlew lint`), detekt step.
- `.github/workflows/release.yml` — verify signed APK upload (already exists, check secrets).
- `app/build.gradle.kts` — add `detekt` plugin + `kotlinx.serialization` (for YAML model if SnakeYAML is too heavy).
- `gradle/libs.versions.toml` — add `snakeyaml`, `detekt`, `mockk` (for unit tests).

---

## Build order
1. `YamlSkill.kt` + `YamlSkillLoader.kt` (data model first)
2. `YamlSkillCompiler.kt` + `SelectorResolver.kt` + `RecoveryExecutor.kt`
3. `SafetyInterceptor.kt` + `PiiFilter.kt`
4. `ScreenFixturePipeline.kt` + `FixtureValidator.kt` + `StalenessChecker.kt`
5. `EntityExtractor.kt` + routing accuracy changes
6. `TelemetryLogger.kt`
7. Tests
8. CI/CD updates

---

## Manual steps (mandatory)
1. **Copy YAML skills to assets:** `cp source_repo/skill_library/skills/*.yaml source_repo/app/src/main/assets/skill_library/skills/`
2. **Add SnakeYAML to `libs.versions.toml`** and sync Gradle (automated below, but Gradle sync requires Android Studio or `./gradlew dependencies`).
3. **Place `.litertlm` model file** on device at the path configured in Settings — no code can do this.
4. **LoRA/SGMV** — not implementable until LiteRT-LM SDK exposes the API. Defer.
5. **GitHub Actions secrets** — `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` must be set in repo Settings → Secrets for release signing.

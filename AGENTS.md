# AGENTS.md — ReturnGift repository memory

## Environment constraints
- No Android SDK / JDK is installed in this sandbox. Gradle builds (`./gradlew assembleDebug`) cannot run here. Code changes are validated by inspection only; runtime QA must happen on a real device with ADB (see `QA_CHECKLIST.md`).
- The repo is a shallow clone; avoid git operations that need full history.

## Architecture facts (verified 2026-08-16)
- The app is a Jetpack Compose app. The homepage / chat is `ComposeChatActivity`; its UI is `ChatScreen.kt` (`ChatTopBar` is the toolbar). Package: `com.returngift.agent`.
- `app_name` in all `strings.xml` locales is already `ReturnGift`. The visible "PokeClaw" title on the homepage was hardcoded in `ChatScreen.kt` `ChatTopBar` (`append("Poke")` + `append("Claw")`) — replaced with "Return"+"Gift".
- The only remaining "PokeClaw" mentions are in `NOTICE` and `EXPERT_REVIEW_TASKS.md` — these are Apache-2.0 attribution to the upstream PokeClaw project and must NOT be removed.

## Screen observation / background execution
- The agent observes the screen via `ClawAccessibilityService.getRootInActiveWindow()` → `getScreenTree()`, exposed to the LLM through the `get_screen_info` tool (`GetScreenInfoTool`). It returns the ACTIVE window's accessibility tree.
- `DefaultAgentService.runAgentLoop` auto-attaches a fresh `get_screen_info` result after action tools (incl. `open_app`) with a `SCREEN_SETTLE_MS = 500ms` settle.
- BUG FIXED (2026-08-16): ReturnGift's chat Activity stayed foreground when a task started, so `getRootInActiveWindow()` returned ReturnGift's own UI after `open_app`. Fix: `TaskFlowController.sendTask` now calls `activity.moveTaskToBack(true)` + shows the floating pill for device-automation tasks (`isDeviceAutomationTask`). Pure chat/device-data queries stay foreground. Auto-return on completion already exists via `autoReturnToChat` (true for `Channel.LOCAL`) in `TaskOrchestrator.onComplete`.

## On-device resources audit
- Local inference: Google **LiteRT-LM** SDK (`com.google.ai.edge.litertlm`, version 0.16.0 in `app/build.gradle.kts`). Runtime in `agent/llm/LocalModelRuntime` + `EngineHolder`; client `LocalLlmClient`. Default models are Gemma-4 (`gemma-4-E2B-it.litertlm`, `gemma-4-E4B-it.litertlm`) downloaded from HuggingFace on demand (`LocalModelManager`).
- **No LoRA** code in the app. The README/CLAUDE.md mentions "LoRA-based Action Skills" as architecture direction, but the actual implementation has NO LoRA adapter loading. "Action Skills" = YAML skill definitions + markdown playbooks, not LoRA weights.
- **SkillOpt**: there is no runtime component literally named "SkillOpt" in the Kotlin app. The skill system is: YAML skills (`skill_library/skills/*.yaml` + `app/src/main/assets/skills/`) loaded by `SkillRegistry`/`YamlSkillLoader`, semantic retrieval via `SkillEmbeddingIndex`, and **playbooks** (`app/src/main/assets/playbooks/*.md`) managed by `PlaybookManager` (markdown step sequences injected into the local LLM system prompt). The Python `skill_library/optimizer/SkillOptimizer` is an offline lifecycle tool, not shipped in the APK.
- So: the APK depends on the on-device **foundation model (Gemma-4 via LiteRT-LM)** as the "brain"; skills/playbooks are generic tool-sequence docs that guide that brain — they are NOT a separate model. Cloud LLMs (OmniRoute/OpenAI/Anthropic) are optional alternatives, not a fallback mode.

## Image inventory in the APK (57 image resource files total)
- Raster PNGs (4): `drawable-nodpi/ic_launcher.png`, `drawable-nodpi/ic_launcher_round.png`, `mipmap/ic_launcher.png`, `mipmap/ic_launcher_round.png`.
- Vector/shape/adaptive XML drawables (53): see `app/src/main/res/drawable/` (49 files incl. `ic_*`/`icon_*` vectors, `bg_*` shapes, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `splash_background.xml`, `web_progress_drawable.xml`) + `mipmap-anydpi-v26/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`, `mipmap-anydpi-v33/ic_launcher.xml`, `mipmap-anydpi-v33/ic_launcher_round.xml`.
- No images in `app/src/main/assets/web/`. One font: `res/font/syncopate_bold.ttf`.

## Mandatory workflow reminders
- Every code change MUST add E2E QA tests to `QA_CHECKLIST.md` and log a Debug Changelog entry (per CLAUDE.md). Done for this change (section F: F7–F10, section U: U1–U5).
- Logging: use `XLog` (e/d/i/w). Errors must be user-visible.

## Release / in-app update (verified 2026-08-16)
- Release workflow: `.github/workflows/release.yml`, triggers on `push: tags: 'v*'`. Builds a signed release APK + GitHub Release via `softprops/action-gh-release@v3`. Requires Actions secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Tag scheme: `v<semver>-<timestamp>` e.g. `v1.0.3-20260815145735`. The release-notes extraction and UpdateChecker both strip the `-suffix` and compare only the semver base.
- **Version source bug (FIXED):** the workflow previously did NOT set `RETURNGIFT_VERSION_NAME`/`RETURNGIFT_VERSION_CODE`, so `app/build.gradle.kts` defaulted to `1.0.0` / code 1 for every release. Now a "Derive APK version from tag" step sets versionName = tag semver and versionCode = `major*10000+minor*100+patch`, injected into env + `local.properties`.
- **UpdateChecker bug (FIXED):** `GITHUB_API` was `https://api.github.com/repos/returngift/returngift/releases/latest` (wrong repo → 404) and had no `User-Agent` header (→ 403). Correct repo is `RevanthBoina/ReturnGift`. Now sends `User-Agent: ReturnGift-App-Updater`, resolves the direct APK asset URL (prefers `ReturnGift-release.apk`), and opens it with `application/vnd.android.package-archive` so Download triggers the installer.
- UpdateChecker is called once per app launch from `ComposeChatActivity.onCreate` (line ~134), throttled to once per 24h via `KVUtils` key `last_update_check`.
- Canonical release asset name: `ReturnGift-release.apk` (matches README badge `https://github.com/RevanthBoina/ReturnGift/releases/latest/download/ReturnGift-release.apk`). The workflow also publishes `ReturnGift.apk` and `SHA256SUMS.txt`.
- Signing key must stay stable across releases or Android refuses to update the installed app (same `applicationId` `com.returngift.agent`, higher `versionCode`, same key).

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

## Action-verification control loop (added 2026-08-18)
- The agent loop now follows observe → resolve target → act → verify state change → continue/recover.
- **Foreground detection**: `ClawAccessibilityService.getForegroundPackage()` uses the active accessibility window root package (fallback: `getWindows()` scan). `isForeground()`, `waitForForeground()` are state-based (not fixed sleeps).
- **Verified app launch**: `ClawAccessibilityService.openAppForeground(pkg, timeoutMs)` returns a `LaunchResult` — success only after the foreground package is verified; handles cached task state (already foreground → no-op), null intent (not installed), `ActivityNotFoundException`/`SecurityException`, and foreground-verification timeout → verified failure state. The old fragile `openApp()` is `@Deprecated` but preserved for legacy callers (`SendMessageTool`, `ContactListUiUtils`, `AutoReplyManager`) that do their own post-launch verification.
- **Dynamic UI grounding**: `SemanticTargetResolver` (agent/grounding) resolves a `TargetDescription` (text/contentDesc/resourceId/viewClass/bounds) to a live node by re-querying the hierarchy each call. Volatile node IDs ("n2"/"n24") are NOT treated as persistent references. `tap_node` prefers semantic params; legacy `node_id` is re-grounded via `resolveByLegacyNodeId` (re-resolves coordinates against the current tree).
- **Keyboard input**: `InputTextTool` verifies editable focus before typing (`waitForEditableFocus`), requests keyboard (`requestKeyboardForFocused`), verifies the entered text (`getFocusedEditableText`), and recovers (re-tap + re-focus) on focus failure.
- **App switching**: `switch_app` tool (verified, handles Recents overlays via `isSystemOverlayLikely` + Back + re-launch). ReturnGift stays background controller; target stays foreground.
- **System-state APIs**: `get_foreground_app` (foreground detection without navigation), `switch_app`, plus existing `get_installed_apps`/`get_device_info`/`get_notifications`. Prefer these over UI navigation.
- **Stall/loop watchdog**: `InteractionWatchdog` (agent/loop) detects consecutive ineffective actions (default threshold 3), cyclic action sequences, unexpected overlays, and stalled transitions; EXECUTES a recovery strategy (RE_QUERY/PRESS_BACK/GO_HOME/RELAUNCH_TARGET/ASK_MODEL_FOR_PLAN) and injects a model hint. Wraps (does not replace) the existing `StuckDetector`.
- **Verification**: `ActionVerifier` (agent/loop) compares foreground package + screen-state signature (`getScreenStateSignature`, stable content hash) + target presence before/after each action.
- **Structured trace**: `ExecutionTracker` DB v2 adds `target_resolution`, `verification_result`, `recovery_action` columns; `recordVerifiedAction()` records them per ACT step.
- **Loop wiring**: `DefaultAgentService.runAgentLoop` captures before-state for ACTION_TOOLS, runs the tool, calls `ActionVerifier.verifyAfter`, feeds `InteractionWatchdog.record`, executes recovery, records via `recordVerifiedAction`, and injects the recovery model hint into the LLM message history. `currentTargetPackage` tracks the target app for relaunch recovery.

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

## Self-development feature (verified 2026-08-18)
- Adds the ability to develop ReturnGift *from* ReturnGift: PR-gated CI/CD that builds APKs, OTA self-update from the freshly built dev APK, and an embedded GitHub code-modification engine.
- **CI/CD**: `.github/workflows/auto_build_and_test.yml` — on PR/push runs `testDebugUnitTest` + `lintDebug` + `assembleDebug`, uploads a `returngift-debug-apk` artifact, and comments the link on the PR; on push to `main` (the `dev-prerelease` job) publishes a rolling `dev-latest` GitHub prerelease whose asset is `ReturnGift-dev.apk` (SHA-256 in body). It is signed with the DEBUG key (not the release key) — install over a debug build only. Stable releases still come from `release.yml` (tag-triggered, release key).
- **Repo rulesets**: `scripts/setup-repo-rules.sh owner/repo` creates a ruleset on `main` requiring 1 approving review + passing `Build, Test & Lint` / `Build Debug APK` checks — enforces the never-push-directly-to-main rule.
- **Embedded code engine**: `app/src/main/java/com/returngift/agent/dev/GitHubCodeEngine.kt` uses the GitHub REST API (Contents read/update, branch creation, PR open) directly via OkHttp — no JGit. It NEVER commits to `main` directly; every change opens a PR from a short-lived `openhands-dev/<8hex>` branch. PAT from `DevConfig`.
- **Syntax pre-validation**: `dev/KotlinSyntaxValidator.kt` runs before any network write — brace/paren/bracket balance, unterminated strings/comments, `TODO("...")` stubs. It is a cheap pre-filter; the authoritative compile gate is CI (`./gradlew testDebugUnitTest` + `lintDebug`). There is no on-device kotlinc.
- **OTA self-update (dev channel)**: `AppUpdateManager.checkForDevUpdate` fetches the `dev-latest` prerelease using the user's PAT (works for private repos, auths the asset download), reuses the existing download/verify/install dialog via `UpdateChecker.showUpdateForRelease`. Throttled to once per 24h via `KVUtils` key `last_dev_update_check` (force bypass from Settings). Reuses `AppUpdateManager`'s SHA-256 + package-name verification. **Note**: the stable-channel 24h throttle (`last_update_check`) is written but never read — still TODO; the dev channel throttle is implemented correctly.
- **Secure config**: `dev/DevConfig.kt` — GitHub repo (owner/name) in MMKV; fine-grained PAT in `EncryptedSharedPreferences` (`returngift_dev_secrets.xml`, AES-GCM via `androidx.security:security-crypto:1.1.0-alpha06`), NEVER plaintext in MMKV. Dev OTA channel toggle in MMKV.
- **Settings**: a new "Developer" group in `SettingsActivity` (layout id `developerGroup` in `activity_settings.xml`): GitHub Repository, GitHub Token (PAT), Dev OTA Channel toggle, Check Dev Update Now, Commit Code Change (opens a PR; runs on `lifecycleScope.launch`).
- **PAT scopes**: fine-grained PAT must have Contents (Read+Write), Pull requests (Write), Metadata (Read). The dev OTA path additionally needs Releases (Read) for private repos.
- **AI disclosure**: PRs opened by the code engine include a note that the PR was created by an AI agent (OpenHands) on behalf of the user; the CI PR comment includes the same note.

## Known CI compile pitfalls (do NOT repeat) — enforced + documented
These three mistakes broke the first `Auto Build & Test` run (PR #47, 2026-08-18). Two are
grep-enforced by `scripts/ci-preflight.sh` (runs as the FIRST CI step, before Gradle, so we
fail in seconds not minutes); the third needs AST analysis and is enforced by review + this
list. Any code change — including one submitted from inside ReturnGift via the embedded
GitHub code engine — MUST NOT reintroduce them.

1. **`android.R.drawable.ic_menu_compose` does not exist.** Only a fixed set of `ic_menu_*`
   drawables ship in the platform (`ic_menu_add`, `ic_menu_edit`, `ic_menu_save`,
   `ic_menu_close_clear_cancel`, `ic_menu_info_details`, `ic_menu_search`, …). Always pick a
   real one. ENFORCED by preflight `no-ic_menu_compose`.
2. **`return` is prohibited in a default parameter value.** `fun f(x: T = y ?: return)` does
   NOT compile — Kotlin forbids non-local return in default-argument expressions. Move the
   null check into the function body: make the param nullable (`x: T? = null`) and
   `val resolved = x ?: default ?: run { ...; return }` at the top of the body. This bit
   `AppUpdateManager.startDownload` (pre-existing latent bug that surfaced in CI).
   ENFORCED by preflight `no-return-in-default-arg` (catches the `?: return` elvis shape).
3. **Bare `this` inside a coroutine lambda is a `CoroutineScope`, not the Activity.**
   `lifecycleScope.launch { AlertDialog.Builder(this) ... }` yields "Argument type mismatch:
   actual type is 'CoroutineScope', but 'Context!' was expected." Always qualify with the
   enclosing Activity: `this@SettingsActivity` (or `this@YourActivity`). NOT grep-enforceable
   (would be all false positives) — enforced by review + this list.
4. **Java tool files in a sub-package that use `ToolResult`/`BaseTool`/`ToolParameter` must
   import them explicitly.** The `tool/impl/tv/*` tools live in
   `com.returngift.agent.tool.impl.tv`, NOT in `com.returngift.agent.tool`, so same-package
   lookup does NOT apply. `VolumeUpTool.java`/`VolumeDownTool.java` were missing
   `import com.returngift.agent.tool.ToolResult;` (pre-existing on main, surfaced once the
   Kotlin compile was fixed and the build reached `:app:compileDebugJavaWithJavac`).
   ENFORCED by preflight `missing-import-ToolResult/BaseTool/ToolParameter` (per-file audit).

The `KotlinSyntaxValidator` shipped with the self-development engine is a brace/quote
checker only; it does NOT catch these (they are valid Kotlin *syntax* but invalid
semantics/platform refs). The authoritative gate is CI (`./gradlew testDebugUnitTest` +
`lintDebug`), fronted by `scripts/ci-preflight.sh`.

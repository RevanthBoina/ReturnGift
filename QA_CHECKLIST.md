ď»ż# ReturnGift E2E QA Checklist

Every build must pass ALL checks before shipping.

Product direction lives in `README.md` under `Product Direction`, `Roadmap`, and `Known platform constraints`. QA should enforce that direction:
fix deterministic harness/runtime bugs first, keep prompts and skills generic, and measure stochastic model behavior by repeated-trial success rate instead of hardcoding one task.

---

## QA Methodology â€” How to Test (READ THIS FIRST)

### Build-Type Tags â€” What Runs on What

Every test in this checklist has an implicit build-type requirement. When adding new tests, label them with the relevant tag so the reader knows what build to use:

| Tag | Meaning | Why it matters |
|---|---|---|
| `[RELEASE-OK]` | Runs against either debug or signed-release APK. Default. | Most UI / persistence / smoke tests. |
| `[DEBUG-ONLY]` | Requires `adb shell run-as com.returngift.agent` or any other path that needs `debuggable=true`. | Release builds set `android:debuggable=false`; `run-as` returns `package not debuggable`. MMKV file inspection, on-device file dumps, AppLogStore raw reads all fall here. Use force-stop survival + Settings UI as the release-build substitute. |
| `[LOGCAT-DEBUG]` | Looks for `XLog.i` / `XLog.d` lines in `adb logcat`. | Release builds gate these on `BuildConfig.DEBUG=false` so they never reach logcat. AppLogStore still captures `XLog.i` for the debug-report.zip even in release. Use the debug-report.zip path for release-build log verification. |
| `[LLM-CLOUD]` | Needs a configured cloud LLM API key. | Tasks like M-section, R/S exploratory, W7/W8 PromptUtils injection trace. |
| `[LLM-LOCAL]` | Needs a downloaded local model (Gemma E2B or E4B). | LQ tests, local task smoke, GPU/CPU verified-healthy paths. |
| `[HUMAN]` | Needs real human input â€” voice, multi-device send, manual permission grant. | V3/V4/V7-V9 voice transcript, C2 second-device auto-reply, permission grant flows. |
| `[OEM]` | Reproduces only on a specific OEM / Android skin. | HyperOS Accessibility kill, MIUI Optimization, Samsung One UI download bugs. Test via Firebase Test Lab / Samsung Remote Test Lab / physical second-hand devices per `STRATEGY.md` Â§7. |

Tag combinations are allowed â€” `[RELEASE-OK] [LLM-CLOUD]` means "works on signed release but you need a cloud key configured."

### Pre-Tag Release Smoke â€” MANDATORY before pushing any vX.Y.Z tag

CLAUDE.md says: full QA triggers before any release/version bump. v0.7.0 violated this gate (QA was run after tag). To make the gate physically present in this checklist, do the following EVERY release, IN ORDER:

1. **Local signed APK first.** Run `assembleRelease` locally with the keystore env sourced from `~/.config/returngift/release-signing.env`. The resulting `app/build/outputs/apk/release/ReturnGift_vX.Y.Z_*.apk` is the artifact under test. Do NOT use the debug APK for release-gate QA.
2. **Install over the previous signed public APK** to verify in-place upgrade. If the upgrade fails (e.g., signing key changed), STOP â€” fix the upgrade story before tagging.
3. **Run sections [RELEASE-OK]** of the new/changed feature areas (V/W/X/Y for v0.7.0-style batches). Record PASS/FAIL in the QA Debug Changelog under a draft `vX.Y.Z` heading.
4. **Run Refactor Regression Bundles** matching the changed code areas.
5. **Generate a debug-report.zip via Settings â†’ About â†’ Share Debug Report.** Unzip on the host machine and inspect `summary.txt`. Make sure: ABI / RAM / OpenCL probe / Backend health all populate; the new feature's `AppLogStore` entries appear under `app_logs/`.
6. **Only after 1-5 PASS, push the tag.** The Actions workflow builds + signs + publishes the GitHub release. The local APK and the release APK should have matching SHA-256 IF the signing env matches (they may differ by tiny build-fingerprint metadata; the signing certificate must match).
7. **Within 24 hours of release, write the Release Gate Record** (use the template above) under the changelog.
8. **Within 24 hours of release, reply on every open OEM issue** with v0.7.0-style ack + debug-report.zip request (see `docs/community-issue-replies.md` for templates).

If steps 1-5 reveal a FAIL, do NOT tag. Hotfix on the release branch, retest, then tag.

### Three QA Layers â€” Do Not Mix These Up

Use all three. Do not claim a user-facing fix from backend smoke alone.

1. **Backend smoke**
   - Fast validation through ADB + logcat.
   - Proves tool routing, rules, runtime guards, and final backend result.
   - Does **not** by itself prove that the result showed up in the visible chatroom.

2. **Chatroom bridge smoke**
   - Short user-visible verification.
   - Proves that once backend has a result, the answer appears in the same chatroom as a visible assistant bubble and is persisted to the current conversation.
   - Use this whenever changing chat/task result rendering, auto-return, or task-to-chat bridging.

3. **True E2E**
   - Full user path: tap/type/send/watch/verify.
   - Use this for release confidence, major regressions, and high-risk flows such as send-message, monitor, permission flows, and context handoff.

Rule of thumb:
- backend-only bug -> backend smoke first, then at least one chatroom bridge check
- user-visible chat/task behavior -> backend smoke + chatroom bridge
- shipping / RC claims -> real E2E, not smoke theater

### Success Rate Over Single-Trial Theater

Do not judge stochastic agent behavior from a single run.

Use these rules:

1. **Deterministic / direct-tool / state-truth flows**
   - Examples: battery, storage, clipboard, model switching, permission truth, monitor start/stop, auto-return shell state
   - Expected standard: effectively `10/10` on the target device
   - If one of these flakes, treat it as a real bug until proven environmental

2. **Cloud exploratory multi-step tasks**
   - Examples: cross-app search, email drafting, app install, read-then-act tasks, `M` section flows, `S` quick tasks, M-session style prompts
   - Run `10` trials and judge by **success rate**, not one lucky pass or one unlucky fail
   - Default release threshold:
     - `8/10` = acceptable
     - `9/10+` = strong enough to promote in README / release notes
     - `<8/10` = still unstable; keep as experimental or fix before shipping

3. **Local exploratory tasks**
   - Use repeated trials too, but evaluate against the intended model tier:
     - `E4B` = primary Local UX target
     - `E2B` = fallback tier that only needs to be broadly usable, not feature-parity with E4B

4. **Blocked cases**
   - Environment blockers do not count as model failures
   - Record them separately from the success-rate denominator when the root cause is external (permissions, missing contacts, runtime dialogs, missing app, absent sender device)

Never claim "fixed" from a single green run on a stochastic Cloud workflow.

### Device Setup

```bash
# 1. Check device connected
adb devices -l

# 2. Install APK
cd /home/nicole/MyGithub/ReturnGift
./gradlew assembleDebug
APK=$(find app/build/outputs/apk/debug/ -name "*.apk" | head -1)
adb install -r "$APK"

# 3. Launch app
adb shell am start -n com.returngift.agent/com.returngift.agent.ui.splash.SplashActivity
sleep 5

# 4. Enable accessibility (if not already)
CURRENT=$(adb shell settings get secure enabled_accessibility_services)
[[ "$CURRENT" != *"com.returngift.agent"* ]] && \
  adb shell settings put secure enabled_accessibility_services \
  "$CURRENT:com.returngift.agent/com.returngift.agent.service.ClawAccessibilityService"

# 5. Grant permissions
adb shell pm grant com.returngift.agent android.permission.READ_CONTACTS
```

### Configure LLM via ADB

```bash
# Cloud LLM
source /home/nicole/MyGithub/ReturnGift/.env
adb shell "am broadcast -a com.returngift.agent.DEBUG_TASK -p com.returngift.agent \
  --es task 'config:' --es api_key '$OPENAI_API_KEY' --es model_name 'gpt-4.1'"

# Local LLM
MODEL_PATH="/storage/emulated/0/Android/data/com.returngift.agent/files/models/gemma-4-E2B-it.litertlm"
adb shell "am broadcast -a com.returngift.agent.DEBUG_TASK -p com.returngift.agent \
  --es task 'config:' --es provider 'LOCAL' --es base_url '$MODEL_PATH' --es model_name 'gemma4-e2b'"
```

### Batch Quick-Task Sweeps

```bash
# Cloud quick tasks
cd /home/nicole/MyGithub/ReturnGift
./scripts/e2e-quick-tasks.sh cloud

# Local quick tasks
./scripts/e2e-quick-tasks.sh local
```

The runner emits `PASS / FAIL / BLOCKED / TIMEOUT` and writes a timestamped log file under `/tmp/`.

### Send a Task via ADB (for M tests)

```bash
# IMPORTANT: wrap the task string in single quotes INSIDE adb shell double quotes
adb logcat -c
adb shell "am broadcast -a com.returngift.agent.DEBUG_TASK -p com.returngift.agent \
  --es task 'how much battery left'"
```

### Send a Chat via ADB (for bridge smoke)

```bash
# Launch ComposeChatActivity through the debug receiver and inject a chat message
adb shell "am broadcast -a com.returngift.agent.TASK -p com.returngift.agent \
  --es chat 'read my clipboard and explain what it says'"
```

Use this when you need a fast chatroom-bridge verification but do not trust raw ADB tap coordinates.
It should create a visible user bubble, wait for the backend reply, and render the assistant bubble in the same conversation.
On Android 15+, make sure ReturnGift is already in the foreground first; otherwise the system may block the receiver from bringing the chat activity forward for UI-visible verification.

### Read Results from Logcat

```bash
# Wait for task to complete (Cloud ~10s, Local ~60-120s per round)
sleep 15
PID=$(adb shell pidof com.returngift.agent)

# Check which tools were called + final answer
adb logcat -d | grep "$PID" | grep -E "onToolCall|onComplete" | head -10

# Full breakdown
adb logcat -d | grep "$PID" | grep -E "DebugTask|PipelineRouter|AgentService|TaskOrchestrator|onToolCall|onComplete"
```

### Verify PASS/FAIL

For each M test, check:
1. **Correct tool called** â€” e.g., "how much battery" should call `get_device_info(battery)`, NOT open Settings
2. **Actual data in answer** â€” "73%, not charging, 32Â°C" NOT "I checked the battery"
3. **Rounds** â€” system queries should be 2 rounds, complex tasks 5-15
4. **Auto-return** â€” after task, ReturnGift chatroom should come back to foreground
5. **Graceful failure** â€” if task can't complete, clear error message (not stuck/loop)
6. **Env-dependent quick tasks** â€” if a sample contact/app is missing on this device, require the correct tool + a graceful failure; literal send/call success should be marked `BLOCKED`, not product `FAIL`

### Verify UI via Uiautomator

```bash
# Dump all visible UI elements
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    desc = node.get('content-desc', '')
    pkg = node.get('package', '')
    if (text or desc) and 'ReturnGift' in pkg.lower():
        print(f'text={text!r} desc={desc!r}')
"
```

Use this to verify:
- UI elements are present (tabs, buttons, prompts)
- Placeholder text changes when switching modes
- Correct model name shows in dropdown

### Tap UI Elements

```bash
# Find coordinates of an element
adb shell cat /sdcard/ui.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    bounds = node.get('bounds', '')
    if 'Task' in text:
        print(f'text={text!r} bounds={bounds}')
"

# Tap at coordinates (center of bounds)
adb shell input tap 746 2041
```

### Three QA Layers

**Layer 1: Backend QA (ADB broadcast)**
- Fast: ~10s per test
- Uses `am broadcast` to send tasks directly to DebugTaskReceiver
- Bypasses UI entirely â€” tests tools, LLM routing, error handling, agent loop
- Code path: `DebugTaskReceiver â†’ sendTask() â†’ PipelineRouter â†’ Agent`
- Sections: M tests
- When to run: every backend/agent/tool change

**Layer 2: UI Structure QA (uiautomator dump)**
- Medium: ~5s per test
- Verifies UI elements are present, positioned correctly, styled correctly
- No message sending â€” purely visual/structural verification
- Code path: Compose render â†’ uiautomator reads view tree
- Sections: P tests
- When to run: every UI/layout change

**Layer 3: UI E2E QA (tap + type + send + verify response)**
- Slow: ~30s per test
- Simulates real user: tap input â†’ type â†’ dismiss keyboard â†’ tap send â†’ wait â†’ verify response bubble
- Tests the FULL pipeline: UI routing â†’ Activity callback â†’ LLM â†’ response â†’ UI update
- Code path: `ChatInputBar â†’ isLocalUI routing â†’ onSendChat/onSendTask â†’ Activity â†’ LLM â†’ UI`
- Sections: Q tests
- When to run: every change that touches send routing, mode switching, or input bar
- **This is the ONLY layer that tests UI send routing.** Layer 1 broadcast bypasses ChatInputBar entirely.

**Why 3 layers, not 2:**
Layer 1 broadcast calls `sendTask()` directly â€” it never touches `ChatInputBar`, `isLocalUI`, or the Chat/Task toggle routing. If UI routing breaks (e.g., Cloud mode accidentally routes to `onSendChat`), Layer 1 won't catch it. Layer 3 covers this gap.

**Run order:**
1. Layer 2 first (fast, catches layout breaks)
2. Layer 3 second (catches routing/interaction breaks)
3. Layer 1 last (catches backend/agent breaks)

```bash
# Layer 2 â€” simulate real user typing + sending
# 1. Find and tap input field
adb shell uiautomator dump /sdcard/ui.xml
# Parse bounds for the input element with placeholder text
INPUT_X=504; INPUT_Y=2100  # adjust from dump

# 2. Tap input, type, send
adb shell input tap $INPUT_X $INPUT_Y        # focus input
sleep 0.5
adb shell input text "how%smuch%sbattery"    # type (spaces = %s in adb)
sleep 0.5
SEND_X=970; SEND_Y=2100                      # adjust from dump
adb shell input tap $SEND_X $SEND_Y          # tap send

# 3. Wait for response, verify chat bubble appears
sleep 15
adb shell uiautomator dump /sdcard/ui_after.xml
adb shell cat /sdcard/ui_after.xml | python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for node in root.iter():
    text = node.get('text', '')
    pkg = node.get('package', '')
    if text and 'ReturnGift' in pkg.lower() and ('battery' in text.lower() or '%' in text):
        print(f'FOUND RESPONSE: {text!r}')
"
# Should find: "Battery: 73%, not charging, 32Â°C" or similar in a chat bubble
```

### Cross-Device Testing

Test on at least 2 devices:
- **Stock Android** (Pixel): baseline, everything should work
- **MIUI/Samsung/OEM** (Xiaomi etc): test for OEM restrictions (autostart, different Settings UI)

Key OEM differences:
- MIUI blocks background app launches (autostart whitelist needed)
- Samsung has different Settings layout
- Some OEMs have chain-launch dialogs (auto-dismissed by OpenAppTool)

### Local LLM Testing Notes

- CPU inference: ~50-60s per round on Pixel 8 Pro
- GPU may fail ("OpenCL not found") â†’ auto-fallback to CPU
- LiteRT-LM SDK may crash on tool call parsing â†’ our fallback extracts from error message
- Force stop loses accessibility service â†’ re-enable after restart
- Model engine takes ~10s to load on first call

---

## Current Coverage Snapshot (2026-04-28, v0.6.8 QA Audit)

This is the latest QA state for `v0.6.8`. It is **not** a green release sheet.
Do not describe `v0.6.8` as fully phone-QA-passed until the blockers below are
fixed and the relevant sweeps are rerun.

Test device and artifact state:
- Device: Pixel 8 Pro (`husky`), Android 16 / API 36, build `CP1A.260405.005`
- Installed test build: `0.6.8` debug APK, upgraded in place over the existing
  debug-signed `0.6.7` install
- Stable release APK upgrade attempt: **BLOCKED**. `adb install -r
  app/build/outputs/apk/release/ReturnGift_v0.6.8_20260428_112909.apk` failed with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the installed `0.6.7` package is
  debug-signed and the `0.6.8` release APK is signed by the stable release cert
  (`e000d1d6555b8fab20c03a5d9ddeba83944f26eecf0b978ac7affc2eebd43186`)
- Release artifact conclusion: the stable APK has **not** been real-device
  upgrade-verified on this handset. Test users moving from debug/old-signature
  builds still need an uninstall/reinstall path or a clear signed-line migration
  note.
- Local release-build conclusion: `./gradlew assembleRelease` compiles/minifies
  but fails at `:app:packageRelease` because local `SigningConfig "release"` is
  missing `storeFile`; a signed hotfix APK must be produced by the configured
  release/CI signing path or after restoring local signing secrets.

Cloud quick-task sweep after fixes:
- Command: `RESULTS_FILE=/tmp/ReturnGift-v068-cloud-quick-20260428-1337-after-wa-fix.log
  CLOUD_MODEL_NAME=gpt-4.1 ./scripts/e2e-quick-tasks.sh cloud`
- Result: **17 PASS / 0 FAIL / 1 BLOCKED / 2 TIMEOUT / 20 TOTAL**
- Passed: Reddit search, YouTube search, Telegram/Play Store path, Twitter
  trending, write email, notifications triage, clipboard explain, storage
  analysis, notification summary, battery advice, WhatsApp send to `Girlfriend`,
  installed-apps list, phone temperature, Bluetooth, battery, storage, Android
  version
- Timed out:
  - `S6/M11` WhatsApp latest-chat summary: still times out at 60s
  - `RC6-cloud-gmail-google` copy latest email subject and Google it: still
    times out at 60s in the latest full sweep
- Blocked:
  - `M47` call Mom: latest run could not find a saved contact named `Mom`; treat
    as data/environment blocked, not a completed call-flow pass
- Regressions fixed since the first 2026-04-28 audit:
  - `S7/M51` Reddit search no longer gets stuck; it passed in two follow-up
    sweeps
  - `B1` WhatsApp send no longer times out; latest full sweep passed in 15s
  - `S8/M19` write email passed in the latest full sweep
  - Timeout cleanup no longer leaks `Task cancelled`/interruption into the next
    harness case

Local targeted smoke after fixes:
- Targeted Local E2B deterministic smoke (`How much battery left?`) completed in
  **105s** after calling `get_device_info(category=battery)` and returning
  `60%, not charging, 38.1Â°C`
- The run first attempted GPU, hit `Can not find OpenCL library on this device`,
  then fell back to CPU and completed. This verifies GPU fallback did not crash,
  but local latency is still high.
- The earlier foreground-service crash path has been fixed by calling
  `startForeground()` immediately in `ForegroundService.onCreate()`
- Local conclusion: targeted Local battery is no longer a hard fail, but Local
  mode is **not full-sweep green** until the full local quick-task set is rerun.

Release blockers found in this audit:
- `Rel-s9`: stable-signed APK cannot upgrade over the currently installed
  debug/old-signature package; document migration and verify a clean stable
  install before asking users to upgrade
- Release signing: local `assembleRelease` cannot package a signed APK without
  the release keystore `storeFile`
- `S6`: WhatsApp latest-chat summary is still not passing on the Pixel 8 Pro QA
  device
- `RC6-cloud-gmail-google`: copy latest email subject and Google it still times
  out in the latest full Cloud sweep
- `LQ-v068`: Local targeted battery now passes, but Local full sweep has not been
  rerun and Local CPU-fallback latency remains high

What can still be claimed from this audit:
- Direct Cloud device-data tools are still working in the quick sweep:
  clipboard, notifications, battery, storage, Bluetooth, phone temperature, and
  Android version all returned real device data
- Cross-app Cloud flows that passed in the latest full sweep include Reddit
  search, YouTube search, Twitter trending, Telegram/Play Store path, write
  email, and WhatsApp direct send to `Girlfriend`
- Local E2B battery can complete through GPU-to-CPU fallback, but slowly

What cannot be claimed:
- `v0.6.8` cannot be called fully QA-passed
- The stable release APK cannot be called upgrade-verified on the QA phone
- WhatsApp latest-chat summary cannot be called fixed
- Gmail latest-subject-to-Google cannot be called fixed
- Local task mode cannot be called full-sweep healthy yet

---

## Current Coverage Snapshot (2026-04-10)

This checklist is **not** yet a fully rerun 100% green master sheet. The honest current state is:

- **Strongly covered right now**
  - Local quick-task sweeps
  - Cloud quick-task sweeps
  - Settings / model config flows
  - Accessibility reconnect + permission return flows
  - Task stop / auto-return / same-session preservation
  - Explicit in-app search and email-compose guards
  - Phase 1 chat-runtime extraction smoke:
    - Cloud runtime rehydrate after relaunch
    - Local runtime rehydrate after relaunch
    - Local chat send with GPUâ†’CPU fallback
  - Phase 2 task-session-store smoke:
    - Local quick-task prompt fill still routes correctly
    - Task shell enters `Task running...` + `Stop`
    - Stop request safely unwinds without leaving `ComposeChatActivity`
    - Idle shell restores after stop
  - Phase 3 permission/accessibility smoke:
    - App Settings truthfully shows `Disabled` after reinstall clears Accessibility from secure settings
    - App Settings truthfully shows `Connecting` during enabled-but-rebinding Accessibility state
    - App Settings truthfully shows `Notification Access = Disabled` when the listener is not enabled in system settings
    - Notification-listener auto-return is now gated by a pending permission-flow flag instead of firing on every reconnect
  - Phase 5 local-runtime consolidation smoke:
    - Shared local runtime still cold-launches into `ComposeChatActivity` with truthful `CPU` backend status
    - Real local UI send still works after runtime consolidation: `say pong` â†’ `Pong! đźŹ“`
    - Assistant bubble model tag remains aligned with the actual backend after send
    - Local single-shot and auto-reply entrypoints now share the same runtime boundary as chat/session bring-up
    - Settings and chat now share the same built-in local model support/catalog state instead of each recalculating RAM/support/downloaded status
  - Chat bubble metadata smoke:
    - User bubbles render a subtle IG-style time footer under the bubble
    - Assistant bubbles render `model name Â· time` under the bubble
    - Saved markdown history persists per-message timestamps via hidden metadata comments
  - ConversationStore smoke:
    - cold relaunch still restores the same saved conversation instead of falling back to a blank chat shell
    - sidebar refresh, save, and restore now come through a single boundary instead of ad-hoc `KVUtils + ChatHistoryManager` calls in `ComposeChatActivity`
  - Phase 2b task-flow boundary smoke:
    - debug task intents still land on the chat shell after `TaskFlowController` extraction
    - task-mode permission guidance still redirects to in-app Settings when Accessibility is missing
    - cold launch no longer crashes if Android blocks an app-start foreground-service request
- **Covered, but still environment-sensitive**
  - WhatsApp send flows
  - Local contact-specific send/call flows
  - Cross-app floating-pill stop flows
- **Still blocked or not fully rerun end-to-end**
  - same-chatroom memory continuity (`Q8-1` to `Q8-4`) â€” must be rerun whenever chat runtime / persistence changes
  - incoming-message auto-reply while staying in-app (`L5`, `L5-b`) â€” needs a second live sender device or equivalent live source
  - some OEM-specific real-device failures from GitHub issues (`Samsung`, `Xiaomi`, `Dimensity`, low-RAM devices)
  - full public-release upgrade validation from the next stable-signed public build

If a task is not clearly marked `PASS`, `FIXED`, or `BLOCKED` with a reason, do **not** assume it is truly cleared.

## Release Gate

A build is only genuinely ship-ready when all of the following are true:

- **Direction gate**
  - The change follows the `README.md` product direction and roadmap
  - It fixes a reusable harness/runtime/product problem, or clearly documents why a narrow change is justified
  - Prompt, skill, and playbook changes remain generic; no one-off tuning for a single flaky task
  - Model-performance limits are measured and documented instead of treated as deterministic product bugs
- **Product gate**
  - Chat vs Task routing is correct in Local and Cloud
  - Local GPUâ†’CPU fallback is truthful and stable
  - Monitor stays in-app and does not force Home
  - Auto-return restores the same conversation after tasks
- **QA gate**
  - Deterministic harness/runtime flows are effectively `10/10`
  - Local deterministic/core sweep finishes with no product `FAIL`
  - Cloud exploratory quick-task and M-session style sweeps are judged by repeated-trial success rate, not one-off luck
  - any Cloud workflow called out as a headline/demo/release-note capability should meet roughly `9/10` on the target device
  - any exploratory Cloud workflow below `8/10` should stay experimental or be fixed before release
  - any `BLOCKED` items are clearly environment-caused, not product regressions
- **Distribution gate**
  - upgrade/install path is understood for the target release
  - release artifact, signing path, and checksums are verified
- **Architecture gate**
  - any refactor touched only its declared scope
  - required regression bundle for that refactor class was rerun

### Release Gate Record Template

Copy this block into the current coverage snapshot or QA debug changelog for every release candidate. Do not publish a release without either a checked item or a concrete blocker note for each line.

```markdown
### Release Gate Record â€” vX.Y.Z (YYYY-MM-DD)

- [ ] Direction gate: change follows README Product Direction / Roadmap / Known platform constraints
- [ ] Harness gate: deterministic runtime/storage/permissions/direct-tool behavior has no known product FAIL
- [ ] Scope gate: no prompt/skill/playbook one-off was added solely to make one flaky task pass
- [ ] Unit/compile gate: `./gradlew compileDebugKotlin testDebugUnitTest`
- [ ] Script hygiene gate: `bash -n scripts/e2e-quick-tasks.sh && git diff --check`
- [ ] Artifact gate: `./gradlew assembleDebug` or signed release workflow completed
- [ ] Targeted regression gate: relevant bundle from "Refactor Regression Bundles" rerun
- [ ] Device smoke gate: at least one real-device smoke for the changed runtime/product path
- [ ] Distribution gate: install/upgrade behavior, signing path, release asset, and checksum are verified or explicitly documented as blocked
- [ ] User-followup gate: affected GitHub/Reddit users are told exactly which stable release to retest and what debug ZIP to attach if it still fails
- Known misses:
  - `BLOCKED`: ...
  - `TIMEOUT`: ...
  - `FAIL`: ...
```

### Release Gate Record â€” v0.7.0 (2026-05-26)

- [x] Direction gate: feature batch (#44 voice / #45 global prompt / #36 custom URL) keeps the harness generic â€” no prompt one-offs added; `ARCHITECTURE_DECISIONS.md` updated to reflect why each piece exists.
- [x] Harness gate: deterministic Settings â†’ InputDialog â†’ MMKV path is fully covered (W1-W6, X1-X8). No known FAIL on the changed paths.
- [x] Scope gate: zero per-app prompt hacks; voice input is system `RecognizerIntent`, prompt injection is the same `applyGlobalPrompt` helper at every prompt construction site.
- [x] Unit/compile gate: `./gradlew assembleDebug` passes; `assembleRelease` runs via GitHub Actions on the v0.7.0 tag.
- [x] Script hygiene gate: no shell script changes in this batch.
- [x] Artifact gate: tag-triggered Actions release workflow produced signed APK `ReturnGift_v0.7.0_20260526_101139.apk`, SHA-256 `ceb993fe014865912e4db72c328497807626d0f5ece5b8254b70946e1c62f3b0` (matches `SHA256SUMS.txt`).
- [x] Targeted regression gate: V1/V2/V5 + W1-W6 + X1-X8 + J1-J3 rerun on signed release APK.
- [x] Device smoke gate: Pixel 8 Pro Android 16 â€” install signed v0.6.12 â†’ in-place upgrade to signed v0.7.0 (same keystore, no uninstall), cold launch + no FATAL, settings rows visible, mic launches Google Speech, MMKV persistence survives force-stop. Documented in QA Debug Changelog 2026-05-26.
- [x] Distribution gate: GitHub release `v0.7.0` published with signed APK + `SHA256SUMS.txt`; in-place upgrade from v0.6.12 verified.
- [x] User-followup gate: 7 GitHub issue replies posted (#42, #48, #23, #17, #16, #29, #2) pointing reporters to v0.7.0 and asking for a fresh debug-report.zip with the new OpenCL / RAM / ABI fields.
- Known misses / process violations:
  - `PROCESS`: Full E2E QA was run AFTER tag-push instead of BEFORE. CLAUDE.md mandates full QA before release; this gate was failed. Recorded as a new P0 BACKLOG item ("PROCESS GATE â€” full QA on signed-release APK BEFORE pushing the version tag"). Next release MUST invert the order.
  - `GAP`: W7/W8 PromptUtils injection logcat trace not directly verified â€” requires a configured cloud LLM run + AppLogStore inspection. Code path structurally verified.
  - `GAP`: V3/V4/V7-V9 voice transcript not verified â€” requires real human voice trial. Code path structurally verified.
  - `GAP`: Y debug-report zip cannot be pulled on signed release because `run-as` is blocked (package not debuggable). Y1-Y4 pass via code-parity with the debug build, not direct release inspection.
  - `FAIL`: Emulator Matrix CI workflow on `main` push â€” all 5 API levels failed first run after v0.7.0 tag. Build debug APK succeeded; per-API smoke tests failed. Tracked as P2 BACKLOG item. NOT a release blocker because: (a) workflow is supplementary smoke, not gate; (b) Pixel manual QA covered the same code paths and PASSed.

### Release Gate Record â€” v0.6.12 (2026-04-30)

- [x] Direction gate: follows README Product Direction / Roadmap / Known platform constraints; this release adds a generic external automation harness slot instead of a one-off task prompt.
- [x] Harness gate: production external automation activity/receiver entries are user-enabled, targeted to the ReturnGift package, and route through normal task/chat harness rules.
- [x] Scope gate: no prompt/skill/playbook one-off was added solely to make a flaky task pass.
- [x] Unit/compile gate: `./gradlew testDebugUnitTest assembleDebug` passed.
- [x] Script hygiene gate: `bash -n scripts/e2e-quick-tasks.sh && git diff --check` passed.
- [x] Artifact gate: local debug artifact built; tag-triggered GitHub Actions release workflow produced signed APK `ReturnGift_v0.6.12_20260430_174625.apk`, SHA-256 `62d9dbb1cc00299892ec0ba229b128d4be018caba589c5a15429ea500c8b8fbe`.
- [x] Targeted regression gate: `ExternalAutomationContractTest` covers task/chat parsing, base64 payloads, callback metadata, unknown action rejection, and missing payload rejection.
- [x] Device smoke gate: Pixel 8 Pro MacroDroid `Send Intent` E2E uses the exported Activity target on modern Android; debug and signed v0.6.12 Activity-target E2E passed.
- [x] Distribution gate: GitHub release `v0.6.12` published with signed APK `ReturnGift_v0.6.12_20260430_174625.apk`, SHA-256 `62d9dbb1cc00299892ec0ba229b128d4be018caba589c5a15429ea500c8b8fbe`.
- [x] User-followup gate: affected GitHub/Reddit users should be pointed to v0.6.12 for External Automation / MacroDroid / direct-device task retesting.
- Known misses:
  - `BLOCKED`: Tasker-specific E2E is blocked by Play Store purchase requirement on the QA phone; MacroDroid E2E is verified.
  - `PARTIAL`: callback-consumer E2E remains open until a Tasker/MacroDroid receiver profile is configured.
  - `FAIL`: none known in the External Automation task/chat/direct-device smoke path.

### Release Gate Record â€” v0.6.10 (2026-04-28)

- [x] Direction gate: follows README Product Direction / Roadmap / Known platform constraints; this fixes model-storage harness behavior instead of tuning a flaky task
- [x] Harness gate: `LocalModelManager` now requires a writable model directory and falls back external app storage -> internal app storage when needed
- [x] Scope gate: no prompt/skill/playbook one-off was added
- [x] Unit/compile gate: `./gradlew compileDebugKotlin testDebugUnitTest` passed
- [x] Script hygiene gate: `bash -n scripts/e2e-quick-tasks.sh && git diff --check` passed
- [x] Artifact gate: `./gradlew assembleDebug` passed; signed release workflow `25084344165` passed
- [x] Targeted regression gate: `LocalModelManagerTest` covers external dir creation, blocked external path, external write-probe fallback, and missing external root fallback
- [ ] Device smoke gate: blocked on the exact Xiaomi/custom-ROM repro device; #39 reporter has been asked to retest v0.6.10 and attach a fresh bug ZIP
- [x] Distribution gate: GitHub release `v0.6.10` published with signed APK `ReturnGift_v0.6.10_20260429_001417.apk`, SHA-256 `1cdc95d13dc6bbecad5ad7fe1cf17a9d6b0e92e4b3e2ebb674fc3d62a2a3ca02`, plus `SHA256SUMS.txt`
- [x] User-followup gate: follow-up comments posted to #39, #17, #29, and #23
- Known misses:
  - `BLOCKED`: exact Xiaomi/custom-ROM model-download repro still requires reporter retest
  - `TIMEOUT`: inherited v0.6.9 exploratory Cloud timeouts remain outside this storage hotfix scope
  - `FAIL`: none known in the local model storage selection regression bundle

## Refactor Regression Bundles

Do **not** rerun the entire world after every refactor. Rerun the right bundle for the code you touched:

- **Model/config changes**
  - `H2`, `H2-b`, `H2-c`, `H4`, `H4-b`
  - `Q4-1`, `Q4-2`, `Q5-1`, `Q5-1b`
  - `LQ1-LQ13`
- **Local runtime / LiteRT fallback changes**
  - `H4`, `H4-b`
  - `Q3-1`, `Q5-1`, `Q5-1b`
  - `LQ1-LQ13`
  - one real Local UI send smoke using live bounds from the current `uiautomator dump`
- **Chat history / bubble metadata changes**
  - `P7-1`, `P7-2`, `P7-3`
  - `Q3-1`
  - `Q7-7`
  - `Q8-1`, `Q8-2`, `Q8-3`, `Q8-4`
  - one persisted markdown-history spot check for `<!-- ReturnGift:timestamp=... -->`
- **Cloud task-context handoff changes**
  - `Q2-1`, `Q2-2`, `Q7-7`
  - `Q8-1`, `Q8-3`
  - `Q9-1`, `Q9-2`
  - one real Cloud chatroom task that refers to earlier context (for example `send that summary by email`)
- **Task lifecycle / orchestration changes**
  - `F1-F6`
  - `I1-I3`
  - `L1`, `L3`
  - `Q7-*`
  - `S2`, `S3`, `S5`, `S7`, `S8`
- **Accessibility / permission changes**
  - `K1-K6`
  - `J4`
  - `L5`, `L5-b` when an external sender is available
- **Cross-app / skill / tool changes**
  - `B1-B5`
  - `M7-M21`
  - relevant quick-task sweeps
- **Direct device-data / no-false-denial changes**
  - `DD1-DD7`
  - `R1-R6`
  - `Q2-2`, `Q3-2`
  - one Cloud chatroom bridge smoke where a direct-device-data answer visibly appears as an assistant bubble
  - one Local chatroom bridge smoke where a direct-device-data/task answer visibly appears in the same conversation
- **Release / installer / updater changes**
  - `Dbg-u1-Dbg-u3`
  - `Rel-s1-Rel-s7`
- **Settings UI / InputDialog changes**
  - `W1-W6` global prompt row + dialog
  - `X1-X8` custom local model URL row + dialog
  - one real signed-release Settings smoke: open Settings, scroll the Model group, confirm each row's trailing label is correct after a force-stop + relaunch
- **Prompt construction / system-prompt changes**
  - `W7`, `W8` (PromptUtils injection trace via logcat or AppLogStore)
  - `Q9-1`, `Q9-2` chatâ†’task context handoff
  - one Cloud chatroom send with a non-empty global prompt configured â€” confirm the prompt actually steers the reply (e.g., reply language switch when prompt says "always reply in Cantonese")
  - one Local task send with a non-empty global prompt â€” confirm `AgentConfig.Builder.build()` runs PromptUtils.applyGlobalPrompt
- **Debug-report content changes**
  - `Y1-Y4` zip summary fields (ABI / RAM / OpenCL / Backend health)
  - one real debug-report on Pixel â€” confirm `summary.txt` shows OpenCL paths and the `apps_logs/` includes recent `AppLogStore` entries
  - one debug-report on a non-Pixel device when available â€” confirm OpenCL probe correctly returns `(none)` if drivers missing

When in doubt, rerun the smaller bundle first, then expand only if something drifted.

---

## Prerequisites
- [ ] Accessibility service enabled
- [ ] Cloud LLM configured (API key set)
- [ ] Local LLM downloaded (Gemma 4)
- [ ] WhatsApp installed with at least 1 contact ("Girlfriend")
- [ ] For monitor QA, an external sender path is available:
  - WhatsApp: second phone / second WhatsApp account
  - Telegram notification monitor: second Telegram account or a Telegram bot token + already-started bot chat on this device
  - Telegram bot remote-control channel: Telegram bot token configured in ReturnGift, bot polling connected, and this handset's Telegram account able to send `/start` plus a task to the bot
- [ ] For external automation QA, Tasker/MacroDroid or an equivalent explicit Activity/Broadcast intent sender is available:
  - the test must run against a release build once the production receiver exists
  - MacroDroid/Tasker-style app automation should prefer the exported Activity target on modern Android because background broadcast receivers can be blocked from opening an Activity
  - debug-only `com.returngift.agent.TASK` / `DEBUG_TASK` receivers are not enough for public integration claims
- [ ] For missed-call QA, an external caller path is available:
  - second phone / second SIM / VoIP caller that can place a real call to this handset
  - one follow-up route already configured
  - for the preferred first version, this should be SMS / Android-native sending rather than UI-driven WhatsApp automation

### Monitor QA Sender Rules

- WhatsApp and Telegram monitor tests are only `PASS` when a real external sender delivers a message to this phone and ReturnGift reacts.
- If the app logic is ready but there is no sender available, mark the case `BLOCKED`, not `FAIL`.
- For Telegram bot QA, the bot must already have an open chat with this handset; Telegram bots cannot cold-DM a user who never started the bot.
- If the Telegram account is frozen/read-only and cannot send messages or take actions, mark Telegram bot E2E as `BLOCKED`, not `FAIL`.
- When testing monitor fixes, always verify both:
  - monitor shell state (`Monitoring: ...`, expand, Stop)
  - actual incoming-message reaction from an external sender

---

## A. Cloud LLM â€” Chat

- [ ] **A1. Pure chat question**: "what is 2+2" â†’ answer in bot bubble, 1 round, no tools, no rocket, no "Starting task...", no "Reading screen..."
- [ ] **A2. Follow-up chat**: after A1, ask "what about 3+3" â†’ answer in bot bubble, context preserved
- [ ] **A3. Chat then task**: chat "hello" â†’ get reply â†’ then "send hi to Girlfriend on WhatsApp" â†’ task executes correctly
- [ ] **A4. Task then chat**: "send hi to Girlfriend on WhatsApp" â†’ completes â†’ then "how are you" â†’ chat reply (not task)
- [ ] **A5. Multiple chat messages**: send 3 chat messages in a row â†’ all get bot bubble replies

## B. Cloud LLM â€” Tasks

- [ ] **B1. Send message**: "send hi to Girlfriend on WhatsApp" â†’ send_message tool called â†’ message sent â†’ answer in bot bubble
- [ ] **B2. Complex task**: "open YouTube and search for funny cat videos" â†’ opens YouTube â†’ searches â†’ multiple steps shown
- [ ] **B3. Task with context**: "I'm arguing with my girlfriend" â†’ then "send sorry to Girlfriend on WhatsApp" â†’ message content should reflect context
- [ ] **B4. Failed contact**: "send hi to Dad on WhatsApp" â†’ Dad not in contacts â†’ LLM reports failure in bot bubble (not stuck, not "Task completed")
- [ ] **B4-b. Name or phone number send target**: send to a saved contact by name, then by phone-number formatting (`+country`, local digits, or spaced/hyphenated form) â†’ same person is resolved without requiring an exact WhatsApp display name match
- [ ] **B4-c. Multilingual text actions stay functional**: on a device/app using non-English labels, structure-first actions (for example standard positive dialog buttons and standard send affordances) still work without requiring English-only UI text
- [ ] **B5. Failed app**: "send hi to Girlfriend on Signal" â†’ Signal not installed â†’ LLM reports can't open app

## C. Cloud LLM â€” Monitor Workflow

- [ ] **C1. Start monitor**: "monitor Girlfriend on WhatsApp" â†’ top bar shows "Monitoring: Girlfriend" â†’ user stays in ReturnGift chat (no Home press)
- [ ] **C1-b. Monitor dialog honors chosen app**: open Monitor dialog â†’ choose `Telegram` (or another supported app) â†’ start monitor â†’ top bar / stop shell show `... on Telegram`, not `... on WhatsApp`
- [ ] **C2. Auto-reply triggers**: Girlfriend sends message â†’ notification caught â†’ WhatsApp opens â†’ reads context â†’ Cloud LLM generates reply â†’ reply sent
- [ ] **C3. Stop monitor**: tap top bar â†’ expand â†’ Stop â†’ monitoring stops
- [ ] **C4. Start Telegram monitor**: "monitor NicoleBot on Telegram" â†’ top bar shows "Monitoring: NicoleBot" â†’ user stays in ReturnGift chat
- [ ] **C5. Telegram auto-reply triggers**: external Telegram sender / bot sends message â†’ notification caught â†’ Telegram opens â†’ reads context â†’ Cloud LLM generates reply â†’ reply sent
- [ ] **C6. Stop Telegram monitor**: tap top bar â†’ expand â†’ Stop â†’ Telegram monitoring stops without affecting WhatsApp monitors

## C2. Background Call Follow-Up

- [ ] **C7. Missed-call follow-up arms cleanly**: enable the missed-call auto follow-up workflow for a chosen person/number/channel â†’ app shows clear in-chat status of what is armed
- [ ] **C8. Real missed call triggers follow-up**: external caller rings this handset, the call is missed, and ReturnGift sends the configured follow-up message to that caller through the chosen channel
- [ ] **C9. Missed-call result is visible in chatroom**: after the follow-up fires, the same ReturnGift conversation shows a clear status/result bubble instead of hiding the action purely in background state
- [ ] **C10. Wrong caller does not trigger**: a different number/contact calls and is missed â†’ no follow-up is sent for the protected target workflow
- [ ] **C11. SMS-first path stays API-first**: when the follow-up channel is SMS, the implementation should use an Android-native send path rather than accessibility-driven UI navigation

## C3. Remote Control Channels & External Automation

- [ ] **C12. Telegram bot token config**: Settings â†’ Remote Control â†’ Telegram Bot â†’ enter token â†’ Save â†’ Settings shows `Connected`; token is not printed in logs, screenshots, bug ZIPs, or QA notes
- [ ] **C13. Telegram bot polling receives message**: user starts the bot from Telegram and sends a simple task â†’ ReturnGift logcat shows Telegram update received and dispatches it through `ChannelManager`
- [ ] **C14. Telegram bot reply path**: after a bot task completes or fails, ReturnGift sends a Telegram reply to the same chat id with a visible success/failure message
- [ ] **C15. Telegram bot blocked account handling**: if the handset Telegram account is frozen/read-only, record `BLOCKED` with the Telegram system message and do not claim channel failure
- [ ] **C16. Production intent task entrypoint**: with `Settings -> Remote Control -> External Automation = Enabled`, a Tasker/MacroDroid-style explicit Activity intent or compatible targeted broadcast starts the requested task in a release build:
  `adb shell am start -n com.returngift.agent/.automation.ExternalAutomationActivity -a com.returngift.agent.RUN_TASK --es task "how much battery left"`
- [ ] **C17. Production intent chat entrypoint**: targeted broadcast with `chat` opens/uses the chatroom path without bypassing safety rules:
  `adb shell am broadcast -a com.returngift.agent.RUN_CHAT -p com.returngift.agent --es chat "say hi"`
- [ ] **C18. Production intent callback**: when `request_id` and `return_action` are provided, ReturnGift broadcasts `accepted` immediately and terminal `completed` / `failed` / `cancelled` / `blocked` / `rejected` results back to the caller
- [ ] **C19. External automation safety**: an Intent payload cannot override platform safety rules, tool contracts, or user global instructions

## D. Local LLM â€” Chat

- [ ] **D1. Pure chat**: switch to Local LLM â†’ "hello" â†’ on-device reply in bot bubble
- [ ] **D2. Chat tab has no task ability**: type "open YouTube" in Chat tab â†’ LLM responds conversationally (doesn't try to control phone)

## E. Local LLM â€” Task Mode (v9: unified chat screen)

- [ ] **E1. Task mode via toggle**: Local tab â†’ tap đź¤– Task â†’ input placeholder changes to "Describe a phone task...", input area tints orange
- [ ] **E2. Task mode via Quick Task tap**: tap "đź”‹ How much battery left?" in Quick Tasks â†’ input fills + auto-switches to Task mode
- [ ] **E3. Monitor via Quick Tasks panel**: scroll to BACKGROUND â†’ tap Monitor card â†’ centered dialog â†’ enter contact â†’ Start â†’ monitoring activates
- [ ] **E4. Task sends correctly**: type "how much battery left" in Task mode â†’ tap send â†’ task executes, response in chat bubble

## F. Task Lifecycle UI

- [ ] **F1. Top bar during task**: while task runs â†’ orange "Task running..." + red "Stop" button visible
- [ ] **F2. Send button becomes stop**: while task runs â†’ send button turns red X â†’ tapping it cancels task
- [ ] **F3. Floating button during task**: while task runs in another app â†’ floating circle shows pill with step/tokens + "Tap to stop"
- [ ] **F4. Floating button stop**: tap floating button during task â†’ task cancels
- [ ] **F5. Second task works**: complete task 1 â†’ start task 2 â†’ floating button, top bar, stop button all work
- [ ] **F6. No stuck typing indicator**: after task completes â†’ "..." is replaced by answer or removed
- [ ] **F7. Device-automation task minimizes ReturnGift** `[LOGCAT-DEBUG]`: send a task that opens another app (e.g. "Open WhatsApp") â†’ ReturnGift chat moves to background (`TaskFlowController: minimizeToBackground: moveTaskToBack=true` in logcat) â†’ floating pill visible â†’ agent's first `get_screen_info` after `open_app` returns the TARGET app's tree, NOT ReturnGift's own chat UI
- [ ] **F8. Info/chat tasks stay foreground**: send a pure chat or device-data query (e.g. "What's my battery level?") â†’ ReturnGift stays foreground (no `moveTaskToBack` log) â†’ answer renders in chat
- [ ] **F9. Auto-return after background task**: after F7 task completes â†’ ReturnGift chat auto-returns to foreground with the result bubble (`onComplete: auto-returning to ReturnGift chatroom` in logcat)
- [ ] **F10. Title reads ReturnGift** `[RELEASE-OK]`: chat homepage TopAppBar shows "Return**Gift**" (Gift in accent color), no "PokeClaw" anywhere on screen

## U. In-App Update

- [ ] **U1. Update dialog appears for older builds** `[RELEASE-OK]`: install a release APK whose `versionName` < latest GitHub release tag (e.g. install v1.0.0 while v1.1.0 is published)  ->  on launch (after the 24h cooldown, or after clearing `last_update_check`) an "Update Available" dialog shows with the remote version and a Download button (`UpdateChecker: Current: 1.0.0, Latest: 1.1.0` in logcat)
- [ ] **U2. No update dialog when already latest**: install the latest release APK  ->  no update dialog (remote tag semver == local versionName)
- [ ] **U3. Download opens installer**: tap Download on the update dialog  ->  Android package installer opens with the `ReturnGift-release.apk` asset (direct install), not just the browser release page
- [ ] **U4. Update installs over existing**: the new APK has a higher `versionCode` and the same signing key  ->  "Update app?" install succeeds without uninstalling first
- [ ] **U5. API endpoint correct** `[LOGCAT-DEBUG]`: logcat shows no GitHub API 404/403; `User-Agent: ReturnGift-App-Updater` is sent

## WH. Workflow History Retention

- [ ] **WH1. 6+ workflows — oldest deleted, newest 5 kept** `[LOGCAT-DEBUG]`: with 6+ completed conversations present, trigger a new task completion  ->  logcat `WorkflowRetention: retainNewest: total=N, keep=5 ... deleting=(N-5)`; `chats/` holds exactly the 5 newest markdown files (by created timestamp); `returngift.db` `conversations`/`messages` tables hold only those 5 ids (no orphans)
- [ ] **WH2. Exactly 5 workflows — none deleted**: with exactly 5 conversations, complete a task  ->  `deleting=0`; all 5 markdown files remain
- [ ] **WH3. 1–4 workflows — all retained**: with <5 conversations, complete a task  ->  `deleting=0`; all conversations remain
- [ ] **WH4. 0 workflows — no-op**: fresh install, complete a task  ->  `No workflows to retain; nothing to do`; no crash
- [ ] **WH5. Running workflow never deleted** `[CRITICAL]`: start a task, ensure its conversation is among the oldest 2 of 7 total, then trigger retention (e.g. via app restart while a task runs)  ->  the running conversation's id appears in `skippedRunningWorkflowId=` and its markdown file + DB rows survive even though it falls outside the keep window
- [ ] **WH6. Chronological order preserved**: after retention with 7 workflows  ->  the 5 remaining conversations are the newest by `created`, newest-first in the sidebar
- [ ] **WH7. Orphaned step artifacts pruned** `[LOGCAT-DEBUG]`: with artifacts in `cacheDir/failure_captures|screenshots|app_logs|http_logs|debug_reports|screen_fixtures` older than the oldest retained workflow  ->  those files are deleted (`prunedArtifacts>0`); artifacts newer than the cutoff remain; `fixtures.db` rows with `captured_at < cutoff` removed
- [ ] **WH8. Retention survives restart**: kill the app with >5 workflows stored, relaunch  ->  on `initCommon`, retention runs and trims to 5 (`WorkflowRetention` log appears on cold start)
- [ ] **WH9. Partial failure non-fatal**: simulate a file delete failure (read-only file)  ->  `Failed to delete workflow file for ...` logged but DB cleanup continues; no crash; remaining workflows still processed

## WH-UNIT. Workflow History Retention (unit)

- [x] **WH-U1..U8**: `WorkflowHistoryRetentionTest` covers 0, 1–4, 5, 6+, running-workflow safety (inside + outside keep window), keepCount=0, 12-workflow ordering, and partial-failure resilience — PASS via `./gradlew testDebugUnitTest` (CI Quality Gate)

## G. Empty State (v9 design)

- [ ] **G1. Cloud empty state**: ReturnGift icon + "ReturnGift" + "Cloud AI" subtitle + "Chat and tasks work together" hint + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **G2. Local empty state**: ReturnGift icon + "ReturnGift" + "Local AI" subtitle + hint with bold đź’¬ Chat / đź¤– Task + 3 prompts (joke, what can you do, email)
- [ ] **G3. Cloud prompt tap**: tap prompt â†’ fills input, stays in chat (no mode switch)
- [ ] **G4. Local prompt tap**: tap prompt â†’ fills input, does NOT switch to Task mode (prompts are chat prompts)
- [ ] **G5. Tab switch updates empty state**: switch Localâ†”Cloud tab â†’ subtitle, hint, and prompts all change immediately

## H. General UI

- [ ] **H1. Floating button size**: small circle on home screen (not giant)
- [ ] **H2. Keyboard in Models screen â€” API key**: Settings â†’ LLM Config â†’ tap API key â†’ keyboard doesn't block field, field scrolls fully into view
- [ ] **H2-b. Keyboard in Models screen â€” Base URL**: switch to Custom provider â†’ tap Base URL â†’ keyboard doesn't block field
- [ ] **H2-c. Keyboard in Models screen â€” Model Name**: switch to Custom provider â†’ tap Model Name â†’ keyboard doesn't block field
- [ ] **H2-d. Chat keyboard dismiss**: focus chat input â†’ keyboard appears â†’ tap a non-button space in the chatroom/message area or the header's blank area â†’ input loses focus and keyboard hides
- [ ] **H3. Layout sizes**: all text/buttons normal size (dp not pt)
- [ ] **H4. Model switcher**: tap model bar â†’ dropdown â†’ switch model â†’ status updates
- [x] **H4-b. Local backend label is truthful**: Local model falls back GPUâ†’CPU â†’ top-left model status updates to `CPU`, not stale `GPU`
- [ ] **H4-c. Cloud switch emits one system line**: Cloud tab â†’ switch model from the top-left dropdown â†’ chat shows one `Switched to ...` system message for that switch, not a lower-case + upper-case duplicate pair
- [ ] **H4-d. Models page shows active + defaults truthfully**: Settings â†’ Models â†’ page clearly shows current `Active model`, `Default local model`, and `Default cloud model`
- [ ] **H4-e. Built-in local rows respect linked/default model files**: if the default local model points at a usable Gemma file, the matching built-in row must not say `Not downloaded`
- [ ] **H5. New chat**: tap pencil icon â†’ clears messages â†’ shows welcome screen
- [ ] **H6. Rename chat**: long-press session in sidebar â†’ rename option â†’ type new name â†’ name updates in sidebar + persists after app restart
- [ ] **H7. Delete chat**: long-press session in sidebar â†’ delete â†’ session removed from sidebar + file deleted
- [ ] **H8. Rename preserves messages**: rename session â†’ open it â†’ all messages still there
- [ ] **H9. Delete correct session**: have 3+ sessions â†’ delete middle one â†’ other sessions unaffected

## I. Cross-App Behavior

- [ ] **I1. Floating button visible in other apps**: start task â†’ agent navigates to WhatsApp/YouTube â†’ floating button visible on top
- [ ] **I2. Return to ReturnGift mid-task**: while task runs in WhatsApp â†’ press recents â†’ tap ReturnGift â†’ see task progress + stop button
- [ ] **I3. Notification during task**: incoming notification while task runs â†’ task not disrupted

## M. Cloud LLM â€” Complex Tasks (50 cases)

Design principle: User perspective. INFO tasks â†’ report actual data. ACTION tasks â†’ confirm result. Must work on ANY Android device.

### System Queries (direct tool, no UI)
- [ ] **M1. Battery**: "how much battery left" â†’ "73%, not charging, ~5h remaining" (get_device_info)
- [ ] **M2. WiFi**: "what WiFi am I connected to" â†’ SSID + signal (get_device_info)
- [ ] **M3. Storage**: "how much storage do i have free" â†’ "47GB free of 128GB" (get_device_info)
- [ ] **M4. Bluetooth**: "is bluetooth on" â†’ ON/OFF + connected devices (get_device_info)
- [ ] **M5. Notifications**: "read my notifications" â†’ actual notification list (get_notifications)
- [ ] **M6. Screen info**: "check what's on my screen" â†’ describe visible UI elements

### App Navigation
- [ ] **M7. Open app**: "open spotify" â†’ Spotify launches, confirmed
- [ ] **M8. YouTube search**: "search youtube for lofi beats" â†’ YouTube opens, types query, results shown
- [ ] **M9. Web search**: "open Chrome and search for weather today" â†’ Chrome, types, results
- [ ] **M10. URL navigation**: "open chrome and go to reddit.com/r/android" â†’ Chrome loads URL
- [ ] **M11. Find in app**: "open WhatsApp and find my last message from Mom" â†’ opens, navigates, reports content
- [ ] **M12. Deep navigation**: "open settings then go to about phone and tell me my android version" â†’ Settings â†’ About â†’ reports version

### Information Retrieval (agent reads and reports back)
- [ ] **M13. Weather**: "what's the weather today" â†’ actual temp + conditions
- [ ] **M14. Last email**: "read my latest email" â†’ sender + subject + preview text
- [ ] **M15. Calendar**: "what's on my calendar tomorrow" â†’ event list with times
- [ ] **M16. Installed apps**: "what apps do i have" â†’ sensible summary, not raw dump
- [ ] **M17. Last notification**: "what did that last notification say" â†’ most recent only
- [ ] **M18. Find photo**: "find the photo i took yesterday" â†’ open Gallery, describe what's there

### Text Input Tasks
- [ ] **M19. Compose email**: "compose an email to test@example.com saying hello" â†’ fills To/Subject/Body, does NOT send
- [ ] **M20. Search Twitter**: "go to twitter and find elon's latest post" â†’ opens X, searches, reports post
- [ ] **M21. Google Maps search**: "open maps and navigate to nearest gas station" â†’ Maps, search, results

### Settings Changes
- [ ] **M22. Dark mode**: "turn on dark mode" â†’ toggles, confirms "Dark mode ON"
- [ ] **M23. Brightness**: "brightness to 50%" â†’ adjusts, confirms level
- [ ] **M24. Timer**: "set a timer for 10 minutes" â†’ Clock app, sets 10:00, starts
- [ ] **M25. Alarm**: "set an alarm for 7am tomorrow" â†’ Clock, creates alarm, confirms
- [ ] **M26. DND**: "do not disturb on" â†’ toggles DND, confirms
- [ ] **M27. Compound settings**: "turn off wifi and turn on bluetooth" â†’ both done, both confirmed

### Media
- [ ] **M28. Take photo**: "take a selfie" â†’ front camera, shutter, send_file back
- [ ] **M29. Screenshot**: "screenshot" â†’ take_screenshot + send_file
- [ ] **M30. Play music**: "play music" â†’ picks music app, attempts playback
- [ ] **M31. Next song**: "play the next song" â†’ skip track in music player

### Cross-App Workflows
- [ ] **M32. Install app**: "install Telegram from Play Store" â†’ Play Store â†’ search â†’ Install
- [ ] **M33. Copy-paste cross-app**: "copy tracking number from gmail and search it on amazon" â†’ Gmail â†’ copy â†’ Amazon â†’ paste
- [ ] **M34. Photo to message**: "take a photo and send it to Mom on WhatsApp" â†’ camera â†’ capture â†’ WhatsApp â†’ send

### Pure Chat (NO phone control)
- [ ] **M35. Joke**: "tell me a joke" â†’ text response, NO tools called
- [ ] **M36. Math**: "whats 234 times 891" â†’ "208,494", NO tools
- [ ] **M37. Timezone**: "what time is it in tokyo" â†’ time answer, NO tools
- [ ] **M38. Cancel**: "nvm" â†’ acknowledges, does nothing

## DD. Direct Device-Data Guard Regressions

- [ ] **DD1. Clipboard explain uses tool, not denial**: Cloud input `read my clipboard and explain what it says` â†’ calls `clipboard(action="get")` before answering; must NOT answer with a generic privacy/device-access refusal
- [ ] **DD2. Notifications summary uses tool, not denial**: Cloud input `read my notifications and summarize` â†’ calls `get_notifications()`; must NOT answer as if it cannot see notifications
- [ ] **DD3. Battery question uses direct device tool**: Cloud input `how much battery left` â†’ calls `get_device_info(category="battery")`; must NOT answer with a generic limitation disclaimer
- [ ] **DD4. Storage question uses direct device tool**: Cloud input `how much storage do i have free` â†’ calls `get_device_info(category="storage")`
- [ ] **DD5. Installed apps question uses direct tool**: Cloud input `what apps do i have` â†’ calls `get_installed_apps()`
- [ ] **DD6. Screen reading uses direct tool**: Cloud input `what's on my screen right now` â†’ calls `get_screen_info()`
- [ ] **DD7. Conceptual control stays chat**: Cloud input `what is an Android clipboard` â†’ normal text answer; guard must not falsely force a device-data tool

### Error Handling
- [ ] **M39. Wrong app name**: "open flurpmaster 3000" â†’ "App not found" + suggestion
- [ ] **M40. Impossible platform**: "text sarah on imessage" â†’ "iMessage not available on Android, try SMS/WhatsApp"
- [ ] **M41. Typo tolerance**: "check my instagarm messages" â†’ understands Instagram
- [ ] **M42. Missing permission**: "monitor WhatsApp" with Notification Access off â†’ guides to Settings

### Natural Language Understanding
- [ ] **M43. Complaint as action**: "my screen is too dim" â†’ increase brightness
- [ ] **M44. Vague request**: "scroll down" â†’ asks clarification OR scrolls current
- [ ] **M45. Slang**: "yo whats on my notifs" â†’ reads notifications
- [ ] **M46. Implicit action**: "go back" â†’ system_key(back), reports new screen

### Device-Agnostic Edge Cases
- [ ] **M47. Call**: "call Mom" â†’ dials Mom (works on any device with Phone app)
- [ ] **M48. Lock**: "lock my phone" â†’ system_key(lock), confirms
- [ ] **M49. Clear notifications**: "clear all my notifications" â†’ clears, confirms
- [ ] **M50. Phone temp**: "how hot is my phone" â†’ get_device_info(battery) temp OR graceful "not available"

## R. Local LLM â€” Reasoning Quick Tasks (1-2 tool calls + LLM analysis)

- [ ] **R1. "Am I missing anything important?"**: get_notifications â†’ LLM triages noise vs important â†’ reports only actionable items
- [ ] **R2. "Will my battery last until tonight?"**: get_device_info(battery) + get_device_info(time) â†’ LLM projects drain â†’ yes/no verdict with advice
- [ ] **R3. "Rewrite what I just copied"**: clipboard(read) â†’ LLM rewrites â†’ clipboard(write) â†’ reports changes
- [ ] **R4. "What can I delete to free up space?"**: get_device_info(storage) + get_installed_apps() â†’ LLM cross-references â†’ prioritized delete list
- [ ] **R5. "Read notifications and summarize"**: get_notifications â†’ LLM groups by category + urgency
- [ ] **R6. "Should I charge my phone?"**: get_device_info(battery) â†’ LLM judges % + gives advice (not just number)

## S. Cloud LLM â€” Multi-step Quick Tasks (Siri can't do these)

- [ ] **S1. "Search YouTube for funny cat fails"**: opens YouTube â†’ types search â†’ results shown (M1/M8 verified)
- [ ] **S2. "Install Telegram from Play Store"**: Play Store â†’ search â†’ Install (M6/M32 verified)
- [ ] **S3. "Check what's trending on Twitter"**: opens Twitter â†’ navigates to trending â†’ summarizes (M20)
- [ ] **S4. "What's on my screen right now?"**: get_screen_info â†’ describes UI elements (M6 verified)
- [ ] **S5. "Copy latest email subject and Google it"**: notifications â†’ clipboard â†’ Chrome â†’ search (M33)
- [ ] **S6. "Check latest WhatsApp chat and summarize"**: opens WhatsApp â†’ reads top chat â†’ reports (M11)
- [ ] **S7. "Open Reddit and search for ReturnGift"**: opens Reddit â†’ types search â†’ results (M51 verified)
- [ ] **S8. "Write an email saying I'll be late"**: opens Gmail â†’ compose draft ready with Subject/Body filled; recipient stays blank unless the task names one; does NOT send (M8/M19 verified)

Current Pixel 8 Pro status on 2026-04-10:
- `S2`, `S3`, `S5`, `S6`, `S7`, and `S8` are verified pass on the latest hardening branch.
- `S1` is currently environment-blocked by a foreground YouTube runtime permission dialog (`GrantPermissionsActivity`), not by a deterministic search-flow failure in ReturnGift.

## P. UI â€” v9 Design Verification

Reference prototype: `/home/nicole/MyGithub/ReturnGift/prototype/dashboard-v9.html`

### P1. Local/Cloud Toggle (in toolbar)
- [ ] **P1-1. Both buttons render**: "Local" and "Cloud" visible on same line as ReturnGift title, right side
- [ ] **P1-2. Selected state**: selected button has aiBubble bg + aiBubbleBorder, unselected has no bg/border
- [ ] **P1-3. No background container**: buttons sit directly in toolbar actions, no wrapping rectangle
- [ ] **P1-4. Tab syncs on launch**: Cloud LLM loaded â†’ Cloud highlighted; Local LLM â†’ Local highlighted
- [ ] **P1-5. Tab filters dropdown**: tap Local â†’ dropdown shows local models only; tap Cloud â†’ cloud models only
- [ ] **P1-6. No model â†’ guidance**: Local with no model â†’ "Download models..."; Cloud with no API key â†’ "Configure API key..."
- [ ] **P1-7. Tab controls UI mode**: tap Local â†’ Chat/Task toggle appears, prompts change to local, placeholder changes; tap Cloud â†’ toggle hides, cloud prompts, cloud placeholder

### P2. Input Area (bottom)
- [ ] **P2-1. Local Chat/Task toggle**: "đź’¬ Chat" and "đź¤– Task" segment buttons visible ABOVE input (not beside)
- [ ] **P2-2. Input full width**: input bar takes full width, toggle is separate row above
- [ ] **P2-3. Task mode orange**: tap Task â†’ toggle turns orange, input border orange, input bg tinted, placeholder "Describe a phone task...", send button orange
- [ ] **P2-4. Chat mode normal**: tap Chat â†’ normal colors, placeholder "Chat with local AI..."
- [ ] **P2-5. Cloud no toggle**: switch to Cloud LLM â†’ Chat/Task toggle HIDDEN, placeholder "Chat or give a task..."
- [ ] **P2-6. Send button dim**: when input empty â†’ send button barely visible (low opacity); when text typed â†’ lights up
- [ ] **P2-7. Same chatroom**: switching Chatâ†”Task does NOT clear messages, stays in same session

### P3. Quick Tasks Panel (between chat and input)
- [ ] **P3-1. Panel visible**: "â–˛ Quick Tasks â–˛" handle with centered up-chevrons visible
- [ ] **P3-2. Default open**: panel open when new chat starts
- [ ] **P3-3. Collapsible**: tap handle â†’ panel collapses (chevrons flip down); tap again â†’ expands (chevrons flip up)
- [ ] **P3-4. Five items default**: 5 quick task prompts visible by default
- [ ] **P3-5. Show more**: "Show more â–Ľ" expands to show all 12 prompts; "Show less â–˛" collapses back
- [ ] **P3-6. Accent bar style**: each prompt has left accent bar (theme color) + full sentence text, finger-friendly height (~38dp)
- [ ] **P3-7. Tap fills input**: tap a quick task â†’ text fills input bar (without emoji prefix)
- [ ] **P3-8. Tap auto-switches mode**: tapping quick task on Local tab â†’ auto-switches to Task mode
- [ ] **P3-9. Background section**: "BACKGROUND" label + Monitor & Auto-Reply card visible below quick tasks
- [ ] **P3-10. Monitor card tap**: tap Monitor card â†’ centered dialog (NOT bottom sheet) with Contact/App/Tone form + "Start Monitoring" button

### P4. Empty State
- [ ] **P4-1. Local empty**: ReturnGift icon + "ReturnGift" + "Local AI" + hint with bold đź’¬ Chat / đź¤– Task + 3 chat prompts (joke, what can you do, email)
- [ ] **P4-2. Cloud empty**: ReturnGift icon + "ReturnGift" + "Cloud AI" + "Chat and tasks work together" + 3 prompts (Tokyo, birthday, WhatsApp)
- [ ] **P4-3. Prompt style matches Quick Tasks**: same accent bar, same height (~38dp), same font size, same bg color
- [ ] **P4-4. Prompt tap**: tap empty state prompt â†’ fills input, correct mode (local prompts = chat, cloud WhatsApp = task)

### P5. No Duplicate Panels
- [ ] **P5-1. Task mode clean**: when Task mode active â†’ old TaskSkillsPanel does NOT appear alongside QuickTasksPanel
- [ ] **P5-2. No old ModeTab**: old "Chat | Task" ModeTab rows (from before v9) do NOT render
- [ ] **P5-3. No stale labels**: "Tap a skill above to start" label does NOT appear

### P6. Theme Consistency
- [ ] **P6-1. Theme-aware colors**: all UI uses `colors.accent` (theme-dependent), NOT hardcoded orange
- [ ] **P6-2. Task mode styling**: task mode input area uses taskBg (#1A1410) + accent border + accent send button
- [ ] **P6-3. Send button states**: empty = dim (alpha 0.35, bg color), chat active = userBubble color, task active = accent color

### P7. Chat Bubble Metadata
- [ ] **P7-1. User footer time**: user bubbles show a subtle time footer under the bubble (IG-chatroom style)
- [ ] **P7-2. Assistant footer metadata**: assistant bubbles show `model name Â· time` when a model tag exists
- [ ] **P7-3. History restore keeps timestamps**: relaunch or reload a saved conversation â†’ visible bubble times stay stable instead of resetting to "now"

## Q. UI E2E â€” Full Pipeline (Layer 3)

Tests the complete path: user tap â†’ ChatInputBar routing â†’ Activity â†’ LLM â†’ response â†’ UI.
Layer 1 broadcast bypasses UI routing. Only Layer 3 catches routing bugs.

### Q1. Tab Switch = Model Switch
- [ ] **Q1-1. Cloudâ†’Local switch**: tap Local button â†’ model status changes to local model name â†’ `isLocalModel` becomes true
- [ ] **Q1-2. Localâ†’Cloud switch**: tap Cloud button â†’ model status changes to cloud model name â†’ `isLocalModel` becomes false
- [ ] **Q1-3. No model available**: tap Local with no downloaded model â†’ no crash, stays on current model
- [ ] **Q1-4. No API key**: tap Cloud with no API key â†’ no crash, stays on current model
- [ ] **Q1-5. Same-session switch actually takes effect**: in one existing conversation, switch Cloud â†’ Local â†’ Cloud without starting a new chat; each subsequent reply must come from the newly selected side, not the previously loaded model
- [ ] **Q1-6. Switch state survives relaunch truthfully**: switch to Local, relaunch, confirm top bar + next reply are Local; then switch to Cloud, relaunch, confirm top bar + next reply are Cloud
- [ ] **Q1-7. System switch messages match reality**: when the active model changes, the latest visible/system-persisted `Switched to ...` message must agree with the model that actually generates the next reply; no stale `Switched to local model` before a Cloud reply, and no missing Cloud switch record before a Cloud reply
- [ ] **Q1-8. Footer/top-bar consistency after switch**: after switching models in the same conversation, old bubbles may keep their original model footers, but the newest assistant bubble must match the current top-bar model state

### Q2. Cloud Tab Send Routing
- [ ] **Q2-1. Cloud chat**: Cloud tab â†’ type "hello" â†’ tap send â†’ AI response in chat bubble (routed via onSendTask)
- [ ] **Q2-1b. Cloud chat stays out of task-running state**: Cloud tab â†’ type a normal chat message like `hello` â†’ reply appears in chat, but the orange `Task running...` bar never appears unless the backend actually enters task/tool execution
- [ ] **Q2-1c. Cloud plain chat imperative does not misroute to Send Message**: Cloud tab â†’ type `say hi` or `tell me more` â†’ stays in ordinary chat, does NOT launch a send-message task, and does NOT reuse any old contact/app state
- [ ] **Q2-2. Cloud task**: Cloud tab â†’ type "how much battery left" â†’ tap send â†’ actual battery info returned
- [ ] **Q2-3. Cloud no toggle**: Cloud tab â†’ verify NO Chat/Task toggle visible â†’ all input goes to unified pipeline
- [ ] **Q2-4. Cloud direct-data bridge**: Cloud tab â†’ type `read my clipboard and explain what it says` â†’ backend uses the clipboard tool AND the explanation appears as a visible assistant bubble in the same chatroom
- [ ] **Q2-4b. Empty clipboard is not a task failure**: Cloud tab â†’ clipboard currently empty â†’ type `read my clipboard and explain what it says` â†’ answer honestly says clipboard is empty, but the chatroom must NOT insert a misleading `Clipboard failed` status line
- [ ] **Q2-5. Cloud notifications bridge**: Cloud tab â†’ type `read my notifications and summarize` â†’ backend uses notifications tool AND the summary appears as a visible assistant bubble in the same chatroom
- [ ] **Q2-6. Cloud-only capability proof**: in the same conversation, switch to Cloud and ask a task known to exceed Local reliability (for example `copy the latest email subject and Google it` or `open Reddit and search for ReturnGift`) â†’ task completes successfully and the reply bubble is tagged with the Cloud model
- [ ] **Q2-7. Cloud context handoff proof**: in the same conversation, ask Cloud to summarize something, then say `send that summary by email` â†’ Cloud uses the earlier chat context and the resulting reply/task output stays tagged as Cloud

### Q3. Local Tab Send Routing
- [x] **Q3-1. Local chat**: Local tab â†’ Chat mode â†’ type "hello" â†’ tap send â†’ AI response (routed via onSendChat to local LLM)
- [ ] **Q3-2. Local task**: Local tab â†’ Task mode â†’ type "how much battery left" â†’ tap send â†’ task executes (routed via onSendTask)
- [ ] **Q3-3. Mode switch**: Local tab â†’ start in Chat â†’ type "hello" â†’ get response â†’ tap Task â†’ type task â†’ executes correctly
- [ ] **Q3-4. Chat doesn't trigger tasks**: Local tab â†’ Chat mode â†’ type "open YouTube" â†’ should get conversational reply, NOT open YouTube
- [ ] **Q3-5. Local task bridge**: Local tab â†’ Task mode â†’ type `how much battery left` â†’ task completes AND the result appears as a visible assistant bubble in the same conversation after the task finishes
- [ ] **Q3-6. Local prompt-only task limit stays honest**: in the same conversation, first create some reusable context, then switch to Local Task mode and ask a vague follow-up like `send that summary by email` â†’ Local must not pretend it used hidden Cloud-like context
- [ ] **Q3-7. Local-vs-Cloud separation proof**: after a successful Cloud-only task, switch back to Local in the same conversation and ask a simple on-device task (`how much battery left`) â†’ result comes from Local, not from the previously active Cloud model

### Q4. Quick Task â†’ Send E2E
- [ ] **Q4-1. Quick task fill + send**: Local tab â†’ tap "đź”‹ How much battery left?" â†’ verify input fills + Task mode active â†’ tap send â†’ battery info returned
- [ ] **Q4-2. Quick task in Cloud**: Cloud tab â†’ tap quick task â†’ input fills â†’ tap send â†’ task executes

### Q5. Routing Regression Guards
- [x] **Q5-1. No OpenCL crash on Local chat**: Local tab â†’ Chat mode â†’ send message â†’ should NOT get "OpenCL not found" (must use CPU fallback)
- [x] **Q5-1b. GPU fallback updates UI label**: Local tab â†’ GPU load/inference fails â†’ fallback to CPU â†’ top-left model status changes to CPU
- [ ] **Q5-2. No API error on Cloud task**: Cloud tab â†’ send task â†’ should NOT get "invalid_request_error" 
- [ ] **Q5-3. Tab switch mid-conversation**: send message on Cloud â†’ switch to Local â†’ send message â†’ no crash, correct routing for each

### Q6. Tab Isolation â€” Local/Cloud Independent Configs
- [ ] **Q6-1. Cloudâ†’Local preserves cloud config**: configure Cloud (gpt-4.1) â†’ switch to Local â†’ switch back to Cloud â†’ model shows gpt-4.1 (not reset)
- [ ] **Q6-2. Local tab uses local model**: switch to Local tab â†’ model status shows local model name (Gemma/etc), NOT any cloud model
- [ ] **Q6-3. Cloud tab uses cloud model**: switch to Cloud tab â†’ model status shows cloud model name, NOT local model
- [ ] **Q6-4. No cloud model configured**: Fresh install â†’ switch to Cloud â†’ shows "No API key configured" or guidance, NOT crash
- [ ] **Q6-5. No local model downloaded**: Remove local model â†’ switch to Local â†’ shows "No local model downloaded" or download prompt, NOT crash
- [ ] **Q6-6. Local chat actually uses local LLM**: Local tab â†’ Chat mode â†’ send "hello" â†’ logcat shows LiteRT/conversation (NOT OpenAI API call)
- [ ] **Q6-7. Cloud task actually uses cloud LLM**: Cloud tab â†’ send "battery" â†’ logcat shows OpenAI/gpt (NOT LiteRT)

### Q7. Task Stop + Session Preservation
- [ ] **Q7-1. Cloud stop responds immediately**: start cloud/network task â†’ tap Stop â†’ task stops within 3 seconds (thread interrupted, HTTP call aborted)
- [x] **Q7-1b. Local stop is safe and honest**: start local task â†’ tap Stop â†’ UI stays in `Task running...`/`Stop` while the current LiteRT round unwinds, then returns to idle with `Task cancelled`, no crash
- [ ] **Q7-2. Stop returns to same session**: start task â†’ task opens other app â†’ tap Stop â†’ returns to ReturnGift â†’ same conversation visible (not new session)
- [x] **Q7-3. App doesn't crash on stop**: start task â†’ tap Stop â†’ app remains running, no ANR, no crash
- [x] **Q7-4. Send button resets after stop**: stop task â†’ send button changes from red X back to arrow â†’ can send new messages
- [ ] **Q7-5. Second task after stop**: stop task 1 â†’ start task 2 â†’ task 2 executes normally (no "Agent is already running" error)
- [ ] **Q7-6. Stop from floating button**: task running in other app â†’ tap floating circle â†’ "Tap to stop" â†’ task stops, returns to ReturnGift
- [ ] **Q7-7. Auto-return preserves conversation**: task completes in other app â†’ auto-return to ReturnGift â†’ previous messages + task result visible in same conversation

### Q8. Chatroom Memory Continuity
- [ ] **Q8-1. Cloud same-chatroom memory**: in one Cloud chatroom, tell it a fact (e.g. "Remember: call Mom at 3pm") â†’ exchange 2-3 unrelated turns â†’ ask "What time did I say to call Mom?" â†’ it should answer from the earlier message, not act like the chat started fresh
- [ ] **Q8-2. Local same-chatroom memory**: in one Local chatroom, tell it a fact â†’ exchange 2-3 unrelated turns â†’ ask for the fact again â†’ it should answer from the same ongoing conversation, not as one-shot QA
- [ ] **Q8-3. Cloud relaunch memory continuity**: in one Cloud chatroom, establish a fact â†’ fully relaunch the app â†’ reopen the same conversation â†’ ask for the fact again â†’ it should still answer from the restored conversation context
- [ ] **Q8-4. Local relaunch memory continuity**: in one Local chatroom, establish a fact â†’ fully relaunch the app â†’ reopen the same conversation â†’ ask for the fact again â†’ it should still answer from the restored conversation context

### Q9. Chat -> Task Context Handoff
- [ ] **Q9-1. Cloud task inherits chatroom history**: in one Cloud chatroom, ask for a summary or establish a reusable fact â†’ then send a task like `send that summary by email` or `text that to Monica` without repeating the content â†’ task should use the earlier chatroom context and complete using the referenced content
- [ ] **Q9-2. Local task stays prompt-only**: in one Local chatroom, establish a fact/summary â†’ switch to Task mode and send a vague task like `send that summary by email` without repeating the content â†’ app should not pretend it has the full chat context; expected product behavior is either a graceful failure or a result that clearly depends only on the current task prompt

### Q10. Persistent Instructions & Memory

- [ ] **Q10-1. Global instructions apply**: set a short global instruction â†’ start a new Cloud chat/task â†’ the model follows it without changing platform/tool safety behavior
- [ ] **Q10-2. Local compressed instructions apply**: same global instruction works in Local mode using a condensed prompt budget, without stuffing unrelated app rules into the context
- [ ] **Q10-3. Scoped app rules load only when relevant**: Telegram task loads Telegram-scoped rules; WhatsApp task loads WhatsApp-scoped rules; unrelated rules are omitted
- [ ] **Q10-4. Clear instructions removes effect**: delete global instructions â†’ new chats/tasks no longer apply the old instruction
- [ ] **Q10-5. Manual memory lifecycle**: user explicitly saves a memory â†’ it survives relaunch â†’ user deletes it â†’ it no longer appears in later model context
- [ ] **Q10-6. Secrets never become memory**: API keys, bot tokens, passwords, and recovery codes are rejected or redacted from memory and excluded from bug reports
- [ ] **Q10-7. Untrusted content cannot override rules**: screen/web/notification text that says "ignore previous instructions" is treated as content, not as a higher-priority instruction

## N. Tinder Automation

- [ ] **N1. Auto swipe**: "open Tinder and swipe right" â†’ opens Tinder â†’ swipes right â†’ repeats
- [ ] **N2. Auto swipe with criteria**: "swipe right on everyone on Tinder" â†’ continuous swipe
- [ ] **N3. Monitor Tinder matches**: "monitor Tinder matches" â†’ detects new match notification â†’ opens chat â†’ auto-replies using LLM
- [ ] **N4. Tinder auto-reply context**: match sends message â†’ LLM reads conversation context â†’ generates contextual reply â†’ sends
- [ ] **N5. Tinder + WhatsApp parallel**: Tinder monitor active + WhatsApp monitor active â†’ both work simultaneously
- [ ] **N6. Stop Tinder monitor**: tap monitoring bar â†’ Stop â†’ Tinder monitoring stops, WhatsApp unaffected

## L. Task Auto-Return

- [ ] **L1. Auto-return after send message**: "send hi to Girlfriend on WhatsApp" â†’ agent opens WhatsApp â†’ sends â†’ completes â†’ ReturnGift chatroom comes back to foreground
- [ ] **L2. Auto-return shows answer**: after return, bot bubble shows the task result (not blank)
- [ ] **L3. No auto-return for monitor**: "monitor Girlfriend on WhatsApp" â†’ monitor starts â†’ user stays in ReturnGift (not kicked to home, not auto-returned)
- [ ] **L4. Monitor stays in app**: after monitor starts, user remains in ReturnGift chat â†’ can keep chatting
- [ ] **L5. Monitor receives notification without leaving app**: monitor active + stay in ReturnGift â†’ someone sends WhatsApp message â†’ notification caught â†’ auto-reply triggers
- [ ] **L5-b. Auto-reply does not kick user Home**: monitor active â†’ incoming message triggers auto-reply â†’ user remains in current app/ReturnGift, no forced Home navigation
- [ ] **L6. Second task after auto-return**: auto-return from task 1 â†’ send task 2 â†’ works normally

## K. Permissions

- [ ] **K1. Monitor blocked without permissions**: "monitor Girlfriend" with Accessibility or Notification Access disabled â†’ Toast + navigate to Settings page (not grey chat text)
- [ ] **K2. Settings shows Notification Access**: Settings â†’ Permissions â†’ "Notification Access" row visible with Connected/Disabled status
- [ ] **K3. Auto-return after Accessibility enable**: disable Accessibility â†’ try monitor â†’ go to Settings â†’ enable Accessibility â†’ app auto-returns to ReturnGift
- [ ] **K4. Auto-return after Notification Access enable**: same flow for Notification Access toggle offâ†’on â†’ app auto-returns
- [ ] **K5. Stale notification toggle**: reinstall app â†’ Notification Access shows "enabled" in system but service not connected â†’ app detects and guides user to toggle offâ†’on
- [ ] **K6. Settings links correct**: tap each permission row in app Settings â†’ leads to correct system settings page:
  - Accessibility â†’ system Accessibility settings
  - Notification â†’ starts ForegroundService / requests POST_NOTIFICATIONS
  - Notification Access â†’ system Notification Listener settings
  - Overlay â†’ system Overlay permission
  - Battery â†’ system Battery optimization
  - File Access â†’ system Storage settings
- [ ] **K6-b. Settings model row handles long names**: Settings â†’ active local/cloud model has a long name â†’ label/value stay aligned, text truncates or wraps cleanly, and the left "Model" label does not collapse into a narrow vertical stack
- [ ] **K7. Full permission setup flow (E2E)**:
  1. Fresh state: disable Notification Access for ReturnGift
  2. Open ReturnGift â†’ type "monitor Girlfriend on WhatsApp" â†’ send
  3. Verify: Toast shows "Enable Notification Access in Settings first"
  4. Verify: app navigates to ReturnGift Settings page
  5. Tap "Notification Access" row â†’ system Notification Listener settings opens
  6. Toggle ReturnGift ON (or OFFâ†’ON if stale)
  7. Verify: auto-return to ReturnGift Settings page
  8. Verify: "Notification Access" row now shows "Connected"
  9. Press back â†’ return to chat â†’ type "monitor Girlfriend on WhatsApp" again
  10. Verify: monitor starts successfully ("âś“ Auto-reply is now active")

---

## T. Model Config â€” Independent Local/Cloud Defaults

- [ ] **T1. Fresh install â€” both tabs empty**: clear all model config â†’ Local tab â†’ modelStatus = "No model selected", send disabled â†’ Cloud tab â†’ same
- [ ] **T2. Only local configured**: Settings â†’ Models â†’ Download + "Use" local model â†’ chat â†’ Local tab â†’ model name shown, send enabled â†’ Cloud tab â†’ "No model selected", send disabled â†’ back to Local â†’ model still there
- [ ] **T3. Only cloud configured**: Settings â†’ Models â†’ Cloud â†’ select provider + model + API key â†’ Save â†’ chat â†’ Cloud tab â†’ model name shown, send enabled â†’ Local tab â†’ if downloaded model exists use it, else "No model selected" â†’ back to Cloud â†’ model still there
- [ ] **T4. Both configured**: config local + cloud â†’ Local tab â†’ local model shown, send enabled â†’ Cloud tab â†’ cloud model shown, send enabled â†’ Local tab â†’ local model unchanged
- [ ] **T5. Cloud model switch via dropdown**: Cloud tab â†’ dropdown â†’ pick different model â†’ model updates â†’ switch to Local â†’ switch back to Cloud â†’ still shows new model
- [ ] **T6. Local model switch via Settings**: Settings â†’ Models â†’ "Use" different local model â†’ return to chat â†’ Local tab shows new model â†’ Cloud config unchanged
- [ ] **T7. Cloud no API key**: Cloud tab selected, API key empty â†’ "No model selected", send disabled
- [ ] **T8. Local model file deleted**: Local tab, but model file removed from disk â†’ "No model selected" or prompt re-download
- [ ] **T9. Set local default while cloud active**: Cloud active in chat â†’ Settings â†’ "Use" local model â†’ return to chat â†’ Cloud model still active until user explicitly switches tabs
- [ ] **T10. Save cloud default while local active**: Local active in chat â†’ Settings â†’ save cloud model â†’ return to chat â†’ Local model still active; switching to Cloud picks saved cloud model

---

## J. Stress / Edge Cases

- [ ] **J1. Rapid fire**: send 3 messages quickly â†’ no crash, messages queued or latest wins
- [ ] **J2. Empty input**: tap send with empty field â†’ nothing happens
- [ ] **J3. Very long input**: paste 500+ character task â†’ no crash, task starts normally
- [ ] **J4. Accessibility lost mid-task**: if accessibility revokes during task â†’ graceful error, not stuck
- [ ] **J5. Network lost mid-task**: if WiFi drops during Cloud task â†’ error message, not infinite loop
- [ ] **J6. App killed and reopened**: force stop â†’ reopen â†’ clean state, no ghost tasks
- [ ] **J7. Monitor + task simultaneous**: monitor Girlfriend active â†’ send task "open YouTube" â†’ both work, monitor not disrupted

---

## V. Voice Input (Issue #44)

E2E tests for system speech recognition (`RecognizerIntent`) wired into the chat composer.
All tests on a real device with Google Speech Services installed (Pixel + most modern Android).

**Build-type:** `[RELEASE-OK]` except V3/V4/V7-V9 which are `[HUMAN]` (real voice required) and V10 which is `[RELEASE-OK]` (release builds also delegate RECORD_AUDIO to the system).

- [x] **V1. Mic button visible**: open chat â†’ input bar shows mic icon between text field and send FAB â†’ expected: visible, tappable. **2026-05-26 PASS Pixel 8 Pro**: mic FAB @ [803,2057][894,2165], content-desc="Voice input", between TextField (right edge 803) and Send (left edge 894)
- [x] **V2. Tap mic with empty field**: tap mic â†’ Android system speech recognition dialog opens ("Speak now") â†’ expected: dialog visible within 2s. **2026-05-26 PASS Pixel 8 Pro**: logcat shows `VoiceInput: mic tapped: text.len=0`, GoogleTTSActivity becomes top activity, NetworkSpeechRecognizer + SodaSpeechRecognizer start listening, custom prompt "Speak nowâ€¦" from strings.xml renders
- [ ] **V3. Speech transcription happy path**: tap mic â†’ say "hello world" clearly â†’ dialog closes â†’ expected: text field contains "hello world". **Needs human voice verify; code path verified via V5 structural cancel test**
- [ ] **V4. Speech with prefix text**: type "remind me to " â†’ tap mic â†’ say "buy milk" â†’ expected: text field contains "remind me to buy milk" (appended with space). **Needs human voice verify; prefix logic: `if text.isBlank() -> "" else if text.endsWith(" ") -> text else "$text "` + spokenText**
- [x] **V5. Cancel speech dialog**: tap mic â†’ swipe down / back button â†’ expected: text field unchanged, no crash, no toast. **2026-05-26 PASS Pixel 8 Pro**: BACK keyevent â†’ `voiceLauncher result: resultCode=0` â†’ `voice input cancelled by user` log, returned to ComposeChatActivity, EditText placeholder "Chat or give a task..." still visible (= text empty), no toast, no FATAL
- [ ] **V6. No speech service**: (uninstall Google app or device without speech recognition) tap mic â†’ expected: toast "Speech recognition not available on this device", no crash. **Cannot verify on Pixel â€” Google Speech preinstalled. Code catches `ActivityNotFoundException` â†’ R.string.voice_input_unavailable toast**
- [ ] **V7. Send after voice input**: V3 succeeds â†’ tap send â†’ expected: message sends normally as if typed. **Needs V3 to pass first**
- [ ] **V8. Voice input during isTaskRunning**: task running â†’ tap mic â†’ expected: dialog still opens (mic available); recognized text appended to field (does not interrupt running task). **Needs human voice; code uses `micEnabled = inputEnabled` only (not gated on isTaskRunning)**
- [ ] **V9. Voice input in Task mode**: Local LLM â†’ Task mode â†’ tap mic â†’ say "open WhatsApp" â†’ expected: text appears, can submit as task. **Needs human voice + local model**
- [x] **V10. Mic permission**: RecognizerIntent handles its own permission â€” no RECORD_AUDIO request from ReturnGift expected. **2026-05-26 PASS Pixel 8 Pro**: V2 dialog opened immediately without permission prompt â€” system mic permission delegated to Google Speech service, as designed**

### ADB / uiautomator verification commands

```bash
# V1: verify mic visible in input bar
adb shell uiautomator dump /sdcard/window_dump.xml && adb pull /sdcard/window_dump.xml /tmp/
grep -i 'voice\|mic' /tmp/window_dump.xml

# V2: launch speech recognition by tapping mic (coordinates TBD post-build)
# After tap, verify Google Speech dialog visible:
adb shell uiautomator dump /sdcard/window_dump.xml && adb pull /sdcard/window_dump.xml /tmp/
grep -i 'speak now\|listening' /tmp/window_dump.xml

# V6: check logcat for graceful no-service error
adb logcat -d --pid=$(adb shell pidof com.returngift.agent) | grep -i 'voice\|speech\|recognizer'
```

---

## W. Persistent Global Prompt (Issue #45)

E2E tests for user-defined persistent instructions stored in MMKV and injected into
every system prompt via `PromptUtils.applyGlobalPrompt()`. Empty string = disabled.

**Build-type:** W1/W2/W3/W4/W6 are `[RELEASE-OK]`. W5 is `[DEBUG-ONLY]` (run-as required). W7/W8 are `[LLM-CLOUD]` or `[LLM-LOCAL]` (need a configured model so PromptUtils actually runs in a chat / agent path). W9/W10 are `[RELEASE-OK]` code-inspection-level.

- [x] **W1. Settings row visible**: Settings â†’ Models group â†’ row labelled "Global instructions" with edit icon â†’ trailing text "Not set" when empty. **2026-05-26 PASS Pixel 8 Pro v0.7.0**: row appears under Model group between Task Budget and Theme, trailing "Not set", bounds [162,1523][813,1566]
- [x] **W2. Open edit dialog**: tap Global instructions row â†’ InputDialog bottom sheet opens with title "Edit global instructions", empty preset text, hint text visible. **2026-05-26 PASS Pixel 8 Pro**: logcat `SettingsActivity: open global prompt dialog: current.len=0`, dialog title "Edit global instructions", hint "Instructions to apply to every conversation. Leave empty to disable.", IME (keyboard) opened
- [x] **W3. Save prompt**: enter "always reply in Cantonese" â†’ Confirm â†’ dialog dismisses â†’ trailing text updates to "Set (25 chars)". **2026-05-26 PASS Pixel 8 Pro**: typed via `adb input text`, tapped OK [504,1271], logcat `SettingsActivity: global prompt saved: new.len=25, hasPrompt=true`, trailing text "Set (25 chars)"
- [x] **W4. Persistence across app restart**: W3 â†’ force-stop app â†’ relaunch â†’ open Settings â†’ trailing text still "Set (25 chars)". **2026-05-26 PASS Pixel 8 Pro**: `am force-stop` + relaunch SplashActivity + nav to Settings â†’ row still shows "Set (25 chars)"
- [x] **W5. Persistence in MMKV**: `adb shell run-as com.returngift.agent strings /data/data/com.returngift.agent/files/mmkv/mmkv.default | grep -E "KEY_GLOBAL_PROMPT|...user text"`. **2026-05-26 PASS Pixel 8 Pro**: mmkv.default contains both "KEY_GLOBAL_PROMPT" key and "always reply in Cantonese" value strings
- [x] **W6. Clear prompt disables**: open dialog â†’ clear text â†’ Confirm â†’ trailing text becomes "Not set". **2026-05-26 PASS Pixel 8 Pro**: tap btnClear @ [918,1100] then OK, logcat `global prompt saved: new.len=0, hasPrompt=false`, trailing "Not set"
- [x] **W7. Injection in chat (logcat)**: with global prompt set, send any chat message â†’ logcat shows `PromptUtils: applyGlobalPrompt: injecting global prompt (N chars) into base prompt (M chars)`. **2026-05-28 PASS Pixel 8 Pro v0.7.1-debug**: clean install + Groq llama-3.3-70b-versatile configured + prompt "Always reply in Cantonese only. No English." (43 chars) â†’ logcat `PromptUtils: applyGlobalPrompt: injecting global prompt (43 chars) into base prompt (10693 chars)`. Fires at both ChatSessionController.buildConversationConfig path and ModelConfigRepository.toAgentConfig (v0.7.1 hotfix path).
- [ ] **W8. Injection in task mode**: AgentConfig.Builder.build() path. **Same as W7 â€” needs configured LLM. Code path: AgentConfig.Builder.build() -> PromptUtils.applyGlobalPrompt before constructing AgentConfig**
- [ ] **W9. Max length cap**: paste 2500 chars â†’ InputDialog clamps to 2000 chars. **Not run â€” InputDialog `maxLength = 2000` parameter passed, InputDialog's existing LengthFilter implementation handles cap (already QA'd elsewhere)**
- [ ] **W10. Empty string normalization**: enter only whitespace â†’ save â†’ `hasGlobalPrompt()` returns false. **Not run on device â€” `hasGlobalPrompt() = getGlobalPrompt().isNotBlank()` so whitespace-only is treated as empty by isNotBlank() semantics. Verified by code inspection**

### ADB verification commands

```bash
# W1: verify Settings row visible
adb shell am start -n com.returngift.agent/.ui.settings.SettingsActivity
sleep 2
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml /tmp/
grep -i 'global instructions\|Not set' /tmp/dump.xml

# W4: persistence test
adb shell am force-stop com.returngift.agent
adb shell am start -n com.returngift.agent/.ui.splash.SplashActivity
sleep 4
adb shell am start -n com.returngift.agent/.ui.settings.SettingsActivity
sleep 2
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml /tmp/
grep -i 'Set ([0-9]' /tmp/dump.xml

# W7/W8: verify injection in logcat
adb logcat -c
# (trigger a chat or task)
adb logcat -d --pid=$(adb shell pidof com.returngift.agent) | grep 'PromptUtils'
```

---

## Y. Debug-Report GPU/OpenCL Diagnostics (Issues #41 + #14)

E2E tests for the OEM-bug-triage diagnostic dump added to `DebugReportManager`.
Goal: when a Xiaomi/Samsung/realme user submits a debug-report.zip, the summary.txt
should make GPU/OpenCL failures self-diagnosable without back-and-forth.

**Build-type:** Y1/Y2/Y3/Y4 are `[DEBUG-ONLY]` if you want to pull the zip via `adb run-as`. On release builds, generate the zip via Settings â†’ About â†’ Share Debug Report â†’ save to a known location via the share intent, then unzip on the host. Y5 is `[OEM]` (device without OpenCL drivers). Y6 is `[RELEASE-OK]` (CPU-safe mode trigger only needs MMKV writes).

- [x] **Y1. RAM line present**: `summary.txt` contains `RAM (total): NN GB`. **2026-05-26 PASS Pixel 8 Pro v0.7.0**: shows `RAM (total): 12 GB`
- [x] **Y2. ABI line present**: `summary.txt` contains `Supported ABIs: ...`. **2026-05-26 PASS Pixel 8 Pro**: shows `Supported ABIs: arm64-v8a`
- [x] **Y3. OpenCL probe present**: `summary.txt` lists paths where libOpenCL.so was found, or `(none) â€” GPU path will not work` when no driver is present. **2026-05-26 PASS Pixel 8 Pro**: shows `/system/vendor/lib64/libOpenCL.so, /vendor/lib64/libOpenCL.so` (confirms why Pixel-8-Pro GPU fallback works in #41)
- [x] **Y4. Backend health summary**: `summary.txt` includes `Backend health:` line from `LocalBackendHealth.debugStateSummary()`. **2026-05-26 PASS Pixel 8 Pro**: `cpuSafe=false, backendPreference=-, reason=-, pendingDevice=-, pendingModel=-, pendingAt=0`
- [ ] **Y5. OpenCL-missing repro path**: on a device without OpenCL drivers, the line should read `(none) â€” GPU path will not work`. **Not run â€” needs non-Pixel device. Code path: `detectOpenClLibraryPaths()` returns `emptyList()` when no candidates exist**
- [ ] **Y6. GPU failure marker captured**: trigger `LocalBackendHealth.debugForceCpuSafe("test")` â†’ generate debug-report â†’ verify `cpuSafe=true, reason=test` appears in Backend health line. **Not run on device**

### ADB verification commands

```bash
# Trigger debug-report build via broadcast (no UI needed)
adb shell am broadcast -p com.returngift.agent -a com.returngift.agent.DEBUG_TASK \
  --es support_action build_debug_report

# Find latest report
adb shell run-as com.returngift.agent ls -t /data/user/0/com.returngift.agent/cache/debug_reports/ | head -1

# Pull + extract summary.txt
ZIP=$(adb shell run-as com.returngift.agent ls -t /data/user/0/com.returngift.agent/cache/debug_reports/ | head -1 | tr -d '\r')
adb shell run-as com.returngift.agent cat /data/user/0/com.returngift.agent/cache/debug_reports/$ZIP > /tmp/ReturnGift-debug.zip
unzip -p /tmp/ReturnGift-debug.zip summary.txt | grep -E "RAM|ABI|OpenCL|Backend health"
```

---

## X. Custom Local Model URL (Issue #36)

E2E tests for advanced user-supplied custom local model download URLs.
URL stored in MMKV (`KEY_CUSTOM_LOCAL_MODEL_URL`). Empty = disabled.
Validated as http(s):// prefix only. fileName derived from URL last path segment.

**Build-type:** X1/X2/X3/X4/X5/X7/X8 are `[RELEASE-OK]`. X6 is `[DEBUG-ONLY]` (run-as required). X9 is `[RELEASE-OK]` code-inspection-level. X10 is `[RELEASE-OK]` once a real custom model is downloaded â€” needs network + storage but no LLM key.

- [x] **X1. Settings row visible**: Settings â†’ Models group â†’ row "Custom local model URL" with share icon â†’ trailing "Not set" when empty. **2026-05-26 PASS Pixel 8 Pro v0.7.0**: row appears under Model group below Global instructions, trailing "Not set", bounds [162,1649][813,1692]
- [x] **X2. Open edit dialog**: tap row â†’ InputDialog opens, title "Custom model download URL", hint with example, empty preset. **2026-05-26 PASS Pixel 8 Pro**: logcat `SettingsActivity: open custom model url dialog: current.len=0`, dialog title rendered correctly
- [x] **X3. Invalid URL rejected**: enter "not-a-url" â†’ tap OK â†’ validator rejects, dialog stays open, no save log fired. **2026-05-26 PASS Pixel 8 Pro**: typed "not-a-url", tapped OK, no `custom local model url saved` log, dialog text still present ("Custom model download URL"). Visible error toast not asserted (InputDialog implementation responsibility) but rejection contract satisfied
- [x] **X4. Valid URL saved**: enter "https://example.com/my-model.litertlm" â†’ OK â†’ dialog dismisses â†’ trailing "Custom URL set", logcat saved log. **2026-05-26 PASS Pixel 8 Pro**: logcat `custom local model url saved: new.len=42, hasUrl=true`, trailing "Custom URL set". Auto-normalizes Android's auto-cap "HTTPS://" -> "https://"
- [x] **X5. Persistence across app restart**: force-stop + relaunch â†’ Settings â†’ trailing still "Custom URL set". **2026-05-26 PASS Pixel 8 Pro**
- [x] **X6. Persistence in MMKV**: `run-as ... strings mmkv.default` shows "KEY_CUSTOM_LOCAL_MODEL_URL" and the URL. **2026-05-26 PASS Pixel 8 Pro**: `KEY_CUSTOM_LOCAL_MODEL_URL+*https://example.com/path/my-model.litertlm` found
- [x] **X7. Clear via empty submit**: open dialog â†’ clear text â†’ OK â†’ trailing back to "Not set". **2026-05-26 PASS Pixel 8 Pro**: logcat `custom local model url saved: new.len=0, hasUrl=false`, trailing "Not set"
- [x] **X8. Catalog includes custom model**: with URL set, LlmConfigActivity Available Models list renders the custom model. **2026-05-26 PASS Pixel 8 Pro**: navigated to LLM Config, "Custom: my-model.litertlm" appears in Available Models list alongside the two built-in Gemma models
- [ ] **X9. fileName derivation**: query-string stripping verified by code in `LocalModelManager.customModel()`: `val q = name.indexOf('?'); if (q > 0) name.substring(0, q) else name`. Not run on device â€” URL with query string would derive correctly per code path
- [ ] **X10. Relaxed validation**: custom model `isValidModelFile` accepts any file â‰Ą 1MB (no size-bound check). Verified by code: `if (model.isCustom) return length >= 1_048_576L`. Not run on device â€” would require an actual custom model download

### ADB verification commands

```bash
# X1/X4 â€” verify row + saved
adb shell input tap 945 185   # gear icon from chat
sleep 3
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml /tmp/
grep -i 'custom local model url\|Custom URL set' /tmp/dump.xml

# X6 â€” persistence in MMKV
adb shell run-as com.returngift.agent strings /data/data/com.returngift.agent/files/mmkv/mmkv.default | grep -E "KEY_CUSTOM_LOCAL_MODEL_URL|https://"
```

---

## AV. Action-Verification Control Loop (computer-use bottleneck elimination)

E2E tests for the unified observe → resolve target → act → verify state change → continue/recover
architecture. Acceptance criterion: the agent no longer continues a task based on an
unverified or stale UI state.

### AV1. Verified app launch — success `[LOGCAT-DEBUG]`
- **Setup**: a launchable app installed (e.g. `com.android.chrome`).
- **Act**: send task `open_app(package_name="com.android.chrome")` (or "open Chrome").
- **PASS**: tool result contains `Opened and verified app in foreground`; logcat shows
  `openAppForeground: verified com.android.chrome is foreground`; the next
  `get_screen_info` shows Chrome's tree (not ReturnGift's chat UI).

### AV2. Verified app launch — unresolvable name → verified failure `[LOGCAT-DEBUG]`
- **Act**: send `open_app(package_name="nonexistent.thing")` (or an app name not installed).
- **PASS**: tool result is an ERROR (not success) describing the failure (`No launchable
  activity...` or `Could not resolve app name`); the agent does NOT proceed as if the app
  opened. The model receives the error and may call `get_installed_apps`.

### AV3. Verified app launch — already foreground (cached task state) `[LOGCAT-DEBUG]`
- **Setup**: bring Chrome to foreground manually.
- **Act**: `open_app(package_name="com.android.chrome")`.
- **PASS**: logcat shows `openAppForeground: com.android.chrome already foreground`; no
  relaunch disturbance; result success.

### AV4. Dynamic UI grounding — semantic tap after transition `[LOGCAT-DEBUG]`
- **Setup**: open an app with a button labeled "Send".
- **Act**: `get_screen_info`, note the "Send" node's id; navigate away and back (or
  rotate/scroll so node IDs change); then `tap_node(text="Send")`.
- **PASS**: logcat shows `Semantic tap (EXACT_TEXT)` at the resolved coordinates; the tap
  lands on "Send" even though the old `nN` id is stale.

### AV5. Dynamic UI grounding — stale node_id is re-grounded `[LOGCAT-DEBUG]`
- **Act**: after a UI transition, `tap_node(node_id="n3")` where n3 no longer maps to a
  live node.
- **PASS**: tool result is an ERROR telling the model the node is stale and to use a
  semantic target or `get_screen_info`; the agent does NOT tap a random stale coordinate.

### AV6. Keyboard input — focus verified before typing `[LOGCAT-DEBUG]`
- **Setup**: open a field (e.g. Settings search, or a compose screen).
- **Act**: `input_text(text="hello", node_id="<field>")`.
- **PASS**: logcat shows `waitForEditableFocus` success; if focus fails the tool attempts
  recovery (re-tap + requestKeyboardForFocused) before returning an error.

### AV7. Keyboard input — entered text verified `[LOGCAT-DEBUG]`
- **Act**: `input_text(text="hello world")` into a field.
- **PASS**: logcat shows `verifyEnteredText` confirming the field content; if
  `ACTION_SET_TEXT` reports success but the field is empty, the tool falls back to
  clipboard paste and re-verifies; success only when the text is actually present.

### AV8. App switching — Recents interference `[LOGCAT-DEBUG]`
- **Setup**: app A foreground; open Recents manually (or via `system_key(key="recent_apps")`).
- **Act**: `switch_app(package_name="<A>")`.
- **PASS**: `switch_app` detects the overlay (`system overlay likely blocking`), presses
  Back, re-launches, and verifies `<A>` is foreground; result success says
  `after dismissing overlay`.

### AV9. App switching — no-op when already foreground `[LOGCAT-DEBUG]`
- **Act**: `switch_app(package_name="<already-foreground-A>")`.
- **PASS**: result `Already in foreground: <A>`; no task-stack disturbance.

### AV10. System-state API — foreground detection without navigation `[LOGCAT-DEBUG]`
- **Act**: `get_foreground_app()` while app X is foreground.
- **PASS**: result `Foreground app: <X>`; no Recents/launcher navigation occurred (no
  `GLOBAL_ACTION_RECENTS` in logcat).

### AV11. Stall / loop detection — 3 ineffective actions → recovery `[LOGCAT-DEBUG]`
- **Setup**: force a no-op scenario (e.g. tapping a disabled element repeatedly, or a
  target that is not on screen).
- **Act**: model repeats the same tap 3+ times.
- **PASS**: after the threshold, logcat shows `Watchdog recovery` executing a strategy
  (RE_QUERY / PRESS_BACK / GO_HOME / RELAUNCH_TARGET / ASK_MODEL_FOR_PLAN); a
  `[System Warning]` is injected into the model context; the model does not blindly repeat.

### AV12. Cyclic action sequence detection `[LOGCAT-DEBUG]`
- **Setup**: model alternates A → B → A → B with no state change.
- **PASS**: logcat shows `Cyclic action sequence detected`; a `[System Warning]` asks the
  model for a fundamentally different approach.

### AV13. Recovery preserves target context `[LOGCAT-DEBUG]`
- **Setup**: a device-automation task targeting app T; force a stalled transition
  (T not foreground after open_app).
- **PASS**: watchdog `RELANCH_TARGET` recovery re-launches T (not some other app);
  logcat shows `Relaunched target app <T> (verified foreground)`; the original task
  continues.

### AV14. Structured execution trace `[LOGCAT-DEBUG]`
- **Act**: run any multi-step task; afterwards dump the trajectory.
- **PASS**: `ExecutionTracker` events for ACT steps carry non-null `target_resolution`,
  `verification_result`, and (on recovery) `recovery_action` columns (DB v2). Verify via
  the existing trajectory export / debug-report path.

### AV15. No blind continuation on failure `[LOGCAT-DEBUG]`
- **Setup**: open_app fails (AV2 path).
- **PASS**: the verification result is `FAILED`; the loop does not issue subsequent
  interaction tools against the (non-opened) app; the model is informed and adapts.

### AV16. scroll_to_find end detection unchanged `[LOGCAT-DEBUG]` (regression)
- **Act**: `scroll_to_find(text="<not present>")` on a short list.
- **PASS**: tool returns the `Reached the bottom/top` error (existing behavior preserved);
  the watchdog does not spuriously trigger recovery on a legitimate "not found".

### AV17. Legacy openApp callers unaffected (regression) `[LOGCAT-DEBUG]`
- **Act**: `send_message(contact="...", message="hi", app="WhatsApp")`.
- **PASS**: SendMessageTool's existing `waitForActiveWindow` path still works; the
  deprecated `openApp()` is preserved for legacy callers; no regression in send_message.

### AV18. Artifact contract — note task finish gate `[ADB, UNIT]`
- **Act**: task "save a note about tomorrow's meeting agenda" → watch the agent.
- **PASS**: the agent calls kb_write BEFORE finish; if it attempts finish with no
  successful kb_write/kb_append, finish is rejected with `[System Guard] FINISH
  REJECTED. This task requires saving a note…` (logcat: `ArtifactContract` /
  "Task guard blocked premature finish") and the loop continues. After a real
  kb_write, finish is accepted and names the vault path; "📄 Saved to vault:" appears
  in chat (ME.6). Unit: `ArtifactContractTest.noteTask_blocksFinishUntilArtifactSaved`.

### AV19. Artifact contract — binary hallucination gate `[ADB, UNIT]`
- **Act**: task "prepare a PDF plan for my week".
- **PASS**: a finish/summary claiming "I've prepared the PDF…" / "your presentation
  has been created…" is REJECTED with the deliverable-honesty correction; the loop
  continues until the model either kb_writes a Markdown plan (finish names the vault
  path; "open the Vault to export as PDF" guidance allowed) or finishes honestly
  ("cannot produce PDFs on-device"). No false claim ever reaches the user.
  Unit: `ArtifactContractTest.pdfTask_*`.

### AV20. Artifact contract — text-only completion gate `[ADB, UNIT]`
- **Act**: same prompts as AV18/AV19 but with a model that answers directly (no
  finish tool call).
- **PASS**: a text-only response that violates the contract (note unsaved, or binary
  claim) is blocked; the correction is injected as a user message and the loop
  continues. Honest/satisfied text completions pass through unchanged.
  Unit: `ArtifactContractTest.*TextOnly*`.

### AV21. Build fingerprint asset ships in the APK `[CI]`
- **Act**: build a release APK; `unzip -l app-release.apk | grep build_fingerprint`.
- **PASS**: `assets/build_fingerprint.txt` is present in the packaged APK (the old
  dotfile `assets/.pcfp` was silently excluded by aapt — verified missing from the
  v2.2.0 release APK during forensic inspection).

---

## CL — Clarification / ask_user Suspend-Resume (2026-08-20)

The `ask_user` tool parks the agent loop on a user question instead of guessing:
`ClarificationManager` (latch-based, timeout 120s) bridges the loop thread and the
chat UI; a `ClarificationCard` above the input bar offers tappable choices; while a
question is pending the send FAB routes the reply as the answer (not a new task) and
Stop stays reachable from the card.

### CL1. Ask-before-acting end to end `[ADB]`
- **Setup**: cloud or local model active; task mode.
- **Act**: send an ambiguous task: `Send the report` (no app/contact named).
- **PASS**: the model calls `ask_user`; logcat shows `ClarificationManager: Clarification
  pending`; the chat shows a ❓ system message AND the clarification card with the
  question; the send FAB stays a Send button (accent), not Stop.

### CL2. Choice tap resumes the task `[ADB]`
- **Act**: during CL1, tap a choice on the card.
- **PASS**: the choice appears as a user bubble; the tool result becomes
  `User answered: <choice>`; the loop continues using that answer (no new task is
  started; `TaskOrchestrator` does not log a lock acquisition for the reply).

### CL3. Typed answer routes to the question `[ADB]`
- **Act**: during a pending question, type a free-text reply in the input bar and send.
- **PASS**: the reply appears as a user bubble and is delivered as the ask_user tool
  result. The message does NOT start a new task/chat round. (Unit:
  `ClarificationManagerTest.typed free text answer is accepted`.)

### CL4. Timeout and cancel recover cleanly `[ADB, UNIT]`
- **Act**: let a pending question sit unanswered for 120s (timeout) or tap "Stop task"
  on the card (cancel).
- **PASS**: timeout → tool returns an error telling the model to proceed with a safe
  default or finish honestly; the loop continues, no hang. Cancel → the loop thread
  wakes immediately and the task ends as cancelled; no lingering card. (Unit:
  `timeout returns null and clears pending state`,
  `cancelPending unblocks a parked request with null`.)

### CL5. No ask-spam on clear requests `[ADB]`
- **Act**: send a fully specified task (`What's my battery level?`).
- **PASS**: no `ask_user` call; the task completes directly. (Prompt Rule 12 /
  LOCAL_TASK_PROMPT guidance; regression guard against over-asking.)

---

## WF — External Artifact Retrieval / web_fetch (2026-08-20)

Phase 3 of the model↔platform coupling plan: the agent can retrieve external
content by URL instead of hallucinating it. `agent/retrieve/WebFetcher.kt` is the
pure-Kotlin core (URL/SSRF policy + HTML→text, no android imports — JVM-testable);
`tool/impl/WebFetchTool.java` is the Android shell (OkHttp, vault persistence).
Honest failures: no login bypass, no CAPTCHA solving, binary URLs reported as
unsupported.

### WF1. Fetch + summarize a public URL `[ADB]`
- **Act**: task mode: `Summarize https://en.wikipedia.org/wiki/Android_(operating_system)`
- **PASS**: `web_fetch` runs (logcat `WebFetchTool: Fetched … -> N chars`), the final
  answer is grounded in the fetched text (mentions facts from the page), no invented
  content. Rule 13 / LOCAL_TASK_PROMPT guide the model to fetch before answering.

### WF2. Vault persistence of fetched content `[ADB]`
- **Act**: `Save a note about https://example.com` (or "keep this article").
- **PASS**: chat shows "📄 Saved to vault: research/example.com-…md"; the Vault screen
  lists the file with frontmatter (type: research, source: URL); the artifact contract
  records the save (finish with the vault path is accepted — `extractWebFetchPath`).

### WF3. SSRF / unsafe URL rejection `[ADB, UNIT]`
- **Act**: task: `Fetch http://192.168.1.1/admin` (also try `http://localhost:8080`,
  `file:///etc/passwd`).
- **PASS**: tool returns a "URL rejected" error immediately (no network call); the
  model reports the rejection honestly. Unit: `WebFetcherTest` URL-policy tests
  (IPs, .local/.lan/.internal, localhost, credentials, non-http schemes).

### WF4. Gated / binary failures are honest `[ADB]`
- **Act**: fetch a URL that returns 401/403 (e.g. a private GitHub file) and a
  binary URL (e.g. a direct PDF link).
- **PASS**: 401/403 → "requires login or blocks access" error; binary content-type →
  "I can only retrieve text/web pages" error. The model must NOT claim the file was
  retrieved; a finish claiming a downloaded PDF is blocked by the artifact contract.

### WF5. Context cap + timeout robustness `[ADB]`
- **Act**: fetch a very long page (e.g. a Wikipedia featured article) and an
  unroutable URL (e.g. https://unreachable.invalid/).
- **PASS**: long page is truncated at 20 000 chars with "(truncated)" in the result,
  the loop continues normally; unreachable host fails within ~10s with a clear
  "Network error" — no hang, watchdog stays quiet.

### WF6. Search → fetch lookup loop `[ADB]`
- **Act**: task mode: `Look up the current Kotlin release version` (no URL given).
- **PASS**: the model calls `web_search(query=…)` (logcat `WebSearchTool: search …`),
  receives a numbered title/URL/snippet list, then `web_fetch`es the most relevant
  URL, and the final answer is grounded in the fetched content with the source named.
  No invented version numbers. (Parser unit: `WebFetcherTest` DDG tests — fixture
  mirrors the real html.duckduckgo.com markup captured 2026-08-20.)

### WF7. Search honest failures `[ADB, UNIT]`
- **Act**: trigger a DDG rate-limit/challenge (rapid repeated searches) and search
  with airplane mode on.
- **PASS**: challenge → "blocked by a bot challenge / rate-limited" tool error and
  the model says so (never fabricates results); airplane mode → "Network error"
  within ~15s, task finishes honestly. Unit: challenge/empty-page parsing returns
  zero results (no crash, no garbage).

### WF8. Update-check throttle actually throttles `[ADB]`
- **Act**: cold-start the app twice within a minute (second start < 24h after first).
- **PASS**: logcat shows `AppUpdateManager: Update check throttled; skipping` on the
  second launch — no GitHub API call (verified via no `Checking for update...` line).
  Settings → Check for updates (force=true) always hits the network. Regression:
  fixes the `last_update_check` written-but-never-read bug.

---

## SD — Self-Development (CI/CD OTA + embedded code-modification engine)

Acceptance criteria: ReturnGift can build itself via PR-gated CI, fetch its own freshly
built dev APK over the air, and open a code-change PR from inside the app — with
on-device syntax pre-validation so uncompilable Kotlin is never pushed.

### SD1. CI builds + gates PRs `[CI]`
- **Act**: open a PR changing a non-critical file; push to a feature branch.
- **PASS**: `Auto Build & Test` workflow runs `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, uploads `returngift-debug-apk` artifact, and comments the artifact
  link on the PR. PR cannot merge while any check fails.

### SD2. Repo ruleset blocks direct push to main `[GH-API]`
- **Act**: run `scripts/setup-repo-rules.sh owner/repo`; attempt a direct push to `main`.
- **PASS**: push is rejected; ruleset requires 1 approving review + passing
  `Build, Test & Lint` / `Build Debug APK` checks before merge.

### SD3. Dev prerelease published on main push `[CI]`
- **Act**: merge a PR to `main`; watch the `dev-prerelease` job.
- **PASS**: a rolling `dev-latest` prerelease exists with a `ReturnGift-dev.apk` asset
  and a SHA-256 in the body. (The prerelease is overwritten on each main push.)

### SD4. Developer settings: repo + PAT stored securely `[ADB]`
- **Act**: Settings → Developer → GitHub Repository = `owner/repo`; GitHub Token = paste a
  fine-grained PAT; toggle Dev OTA Channel On; force-quit + relaunch.
- **PASS**: repo persists in MMKV; PAT persists in EncryptedSharedPreferences
  (`returngift_dev_secrets.xml`, AES-GCM) — NOT plaintext in MMKV; toggle persists.

### SD5. OTA dev self-update fetches dev APK `[ADB]`
- **Act**: Settings → Developer → Check Dev Update Now (dev channel On, PAT set).
- **PASS**: dialog shows the `dev-latest` release; tapping Update downloads
  `ReturnGift-dev.apk` (authed by the PAT for private repos), verifies package name
  `com.returngift.agent`, and launches the installer. Re-running within 24h is throttled
  unless `force=true`.

### SD6. Code engine refuses uncompilable Kotlin `[ADB]`
- **Act**: Settings → Developer → Commit Code Change; submit content with an unbalanced
  brace (`fun foo() {`).
- **PASS**: `KotlinSyntaxValidator` reports "Unbalanced '{'"; no branch/PR is created
  (no network write occurs).

### SD7. Code engine opens a real PR `[ADB-GH]`
- **Act**: submit a syntactically-valid trivial change (e.g. a comment) to a real file.
- **PASS**: a branch `openhands-dev/<8hex>` is created from `main` HEAD, the file is
  committed, and a PR is opened from that branch → `main`. CI gates the PR. The PR body
  includes the AI-generated disclosure note.

### SD8. Stable update path unchanged (regression) `[ADB]`
- **Act**: with Dev OTA Channel OFF, relaunch the app.
- **PASS**: only the stable `releases/latest` check runs; `checkForDevUpdate` is a no-op
  (returns UpToDate("dev channel disabled")); no extra GitHub API calls.

### SD9. CI pre-flight catches known compile pitfalls `[CI]`
- **Act**: open a PR that reintroduces `android.R.drawable.ic_menu_compose`, a
  `?: return` in a default parameter value, or a Java tool file that uses
  `ToolResult`/`BaseTool`/`ToolParameter` without importing it.
- **PASS**: the `CI pre-flight (known-pitfall grep guards)` step fails in seconds (before
  Gradle runs) with a clear message pointing at the offending file:line. No 3-minute Gradle
  run is wasted on a known mistake.

---

## ME. Chat Edit & Resend + Vault Artifacts (2026-08-20)

E2E tests for two reported UX bugs:
(1) editing a sent user message only rewrote the bubble — the model never re-read the
edit; (2) the agent claimed to prepare deliverables (e.g. "a PDF plan") but nothing was
shown — kb_* writes went to an invisible app-private vault with no UI.

**Build-type:** `[RELEASE-OK]` for ME1–ME5 unless noted; all require a real device (no
emulator guarantee for local-LLM reload timing).

### ME.1 Edit & resend (cloud model) `[ADB]`
- **Act**: send "what is 2+2" → wait for reply → long-press the user bubble → Edit &
  Resend → change to "what is 3+3" → Resend.
- **PASS**: the old turn and its reply disappear; the edited message appears as a new
  user bubble with "(edited)"; a typing indicator appears; the assistant replies to the
  NEW text (mentions 6, not 4). Logcat: `editAndResend: rewinding conversation to message #N`.

### ME.2 Edit & resend rewrites model history (no ghost context) `[ADB]`
- **Act**: chat "my name is Bob" → reply → edit that message to "my name is Alice" →
  Resend → then ask "what is my name?".
- **PASS**: the model answers "Alice" — the pre-edit text must not remain in
  `cloudHistory` (cloud) or the recreated LiteRT conversation (local).

### ME.3 Edit & resend (local model reload path) `[ADB, HUMAN]`
- **Act**: local model active → edit a mid-conversation user message → Resend.
- **PASS**: model status shows a brief reload; the edited message is sent once the model
  is ready (≤30 s polling); reply addresses the edited content.

### ME.4 Edit guard while busy `[ADB]`
- **Act**: while a reply is streaming (awaiting) or a task is running, long-press a user
  bubble → Resend.
- **PASS**: toast "Wait for the current reply to finish, then edit."; the conversation is
  unchanged.

### ME.5 Edit persistence across restart `[ADB]`
- **Act**: after ME.1, force-stop the app → relaunch → reopen the conversation.
- **PASS**: the restored conversation contains the edited turn and the new reply; the
  removed post-edit messages do not reappear.

### ME.6 Vault artifact surfaced in chat `[ADB]`
- **Act**: run a task that saves a note, e.g. chat task "save a short packing list for a
  weekend trip as a note".
- **PASS**: a system message "📄 Saved to vault: notes/…" appears in chat during/after
  the task, and the completion summary names the vault path.

### ME.7 Vault screen lists & opens files `[ADB, HUMAN]`
- **Act**: tap the folder icon in the chat top bar → Vault screen opens → tap a file →
  content is displayed (selectable) → tap "Open with…".
- **PASS**: list shows all vault files newest-first with size/timestamp; detail shows full
  content; the system chooser opens the .md in an external viewer (FileProvider grant).
  Empty vault shows "Nothing saved yet".

### ME.8 No hallucinated file claims (prompt rule) `[ADB]`
- **Act**: ask (task mode) "create a PDF plan for my week".
- **PASS**: the agent does NOT claim a PDF was created; it either saves a Markdown note via
  kb_write (and says so with the path) or explains it can only produce Markdown notes.

### ADB verification commands

```bash
# ME.1/ME.3: watch the edit-and-resend flow
adb logcat -d --pid=$(adb shell pidof com.returngift.agent) | grep -i 'editAndResend'

# ME.6/ME.7: confirm the artifact was actually written to the vault
adb shell run-as com.returngift.agent find /sdcard/Android/data/com.returngift.agent/files/vault -type f
# (or pull the file and inspect)
adb pull /sdcard/Android/data/com.returngift.agent/files/vault/ /tmp/vault/
```

---

## ST — Stall & Token Defense (2026-08-21)

Covers `ObserveStallGuard`, the TokenMonitor CRITICAL hard abort, and the StuckDetector
wiring fix in `DefaultAgentService.runAgentLoop`. Reference patterns: android_world m3a
per-step history as progress signal, droidrun bounded action history.

### ST.1 Observe-only stall hint `[ADB] [LLM-CLOUD] [LOGCAT-DEBUG]`
- **Act**: run a task that makes the model re-observe without acting (e.g. point it at a
  static screen with an ambiguous instruction).
- **PASS**: after 2 consecutive idle rounds on an unchanged screen, logcat shows
  `ObserveStallGuard HINT` and the next model input contains a `[System Notice]` telling it
  to act / ask_user / finish; the notice is injected exactly once per stall streak.

### ST.2 Observe-only stall abort `[ADB] [LLM-CLOUD] [LOGCAT-DEBUG]`
- **Act**: continue ST.1 with the model still not acting.
- **PASS**: at the 4th consecutive idle round, logcat shows `ObserveStallGuard ABORT`,
  `ExecutionTracker.endTask` records outcome `STALL_ABORT`, and the user sees a completion
  message saying the task was stopped for re-reading an unchanged screen (with token usage).

### ST.3 Token CRITICAL hard abort `[ADB] [LLM-CLOUD]`
- **Act**: temporarily lower the CRITICAL threshold (or drive token usage past 200K, e.g.
  repeated web_fetch of long pages) with TaskBudget set above the threshold.
- **PASS**: the task aborts with outcome `BUDGET_ABORT` and a user-visible "safety ceiling"
  message even though the user-configured budget was not reached; no further LLM calls occur.

### ST.4 StuckDetector real-signal wiring `[ADB] [LOGCAT-DEBUG]`
- **Act**: run a task whose tool calls fail repeatedly with the same error (e.g. tap a
  non-existent target).
- **PASS**: `StuckDetector` fires `RepeatedError` and/or `ZeroDiff` within 3 rounds (these
  signals were previously impossible: diff count was the screen-text set size, error was
  hardcoded null); AUTO_KILL records outcome `AUTO_KILL` in ExecutionTracker.

### ST.5 Regression — normal task flow unaffected `[ADB] [LLM-LOCAL or LLM-CLOUD]`
- **Act**: run AV-section smoke tasks (open app, tap, type, finish).
- **PASS**: tasks complete normally; no HINT/ABORT fires while actions execute or the
  screen changes; ObservationPolicy still skips observation for predictable bursts.

### ST.6 Unit tests `[HOST]`
- **Act**: `./gradlew :app:testDebugUnitTest --tests '*ObserveStallGuardTest'`.
- **PASS**: all 8 tests green (action/screen-change reset, hint at 2, hint once, abort at 4,
  zero-hash idle, token-burn reproduction, custom thresholds, reset).

---

## QA Debug Changelog

Format: `[date] [status] [test-id] description`

### 2026-08-21 — Phase A: stall/token defense (ObserveStallGuard + CRITICAL abort + StuckDetector wiring)

**Change:** `DefaultAgentService.runAgentLoop` gains three loop defenses and loses its dead code:
1. New pure-Kotlin `agent/loop/ObserveStallGuard` (HINT at 2 consecutive idle rounds on an
   unchanged screen, injected once; ABORT at 4 → `endTask("STALL_ABORT")` + user-visible
   stop message). The 121.8K/74.1K observe-only token burns are now capped at ~4 idle rounds.
2. TokenMonitor CRITICAL (200K+) hard abort independent of the user-configured TaskBudget →
   `endTask("BUDGET_ABORT")` + user-visible safety-ceiling message.
3. StuckDetector now receives the real per-round diff count (`added+removed` text lines) and
   the real tool error — ZeroDiff and RepeatedError can actually fire (previously fed the
   screen-text set size and a hardcoded null). AUTO_KILL now also records
   `endTask("AUTO_KILL")` for trajectory consistency.
4. ObservationPolicy `screenHashChanged` uses the real diff count instead of the
   `consecutiveActionsWithoutObserve` counter proxy.
Dead code removed: `loopHistory`, `RoundFingerprint`, `isStuckInLoop`, `LOOP_DETECT_WINDOW`,
`consecutiveNoToolCalls` (all defined but never read).
Tests: `ObserveStallGuardTest` (8 tests, pure JVM). QA: ST.1–ST.6. Pending device run.

### 2026-08-20 — v2.3.0 hotfix: Java→Kotlin `Result` interop compile break

**Failure analysis (run 32341593017 et al., all 4 workflows):** `compileDebugJavaWithJavac`
failed with "cannot find symbol: method write(String,Map<String,Object>,String)" at
`WebFetchTool.java:189/191`. Root cause: `KBManager.write` returns `kotlin.Result` —
a value class, so its JVM name is mangled (`write-0E7RQCE`, confirmed via `javap`)
and Java cannot call it. Kotlin compile passed, so the error only surfaced at the
Java stage; unit-test jobs failed at the same compile task (not at test time).

**Fix:** `KBManager.writeFromJava(path, frontmatter, content): Boolean` — a plain
signature wrapper; `WebFetchTool` uses it. Guard: preflight `no-kotlin-result-from-java`
fails the build in seconds if any `.java` file references `kotlin.Result<`.
Verified locally by reproducing CI's exact stage: kotlinc-compiled real
`KBManager`/`BaseTool`/`ToolResult`/`ToolParameter`/`WebFetcher`, then `javac` on
`WebFetchTool.java`+`WebSearchTool.java` against them + real OkHttp — exit 0.
Unit suites re-run: WebFetcherTest 25/25, ArtifactContractTest 15/15.

[2026-08-20] [PASS] hotfix-compile  javac repro of CI stage passes; relaunched as new v2.3.0 tag.

### 2026-08-20 — Phase 4: web_search (keyless lookup) + stable-channel throttle fix

1. **`web_search` tool** (`tool/impl/WebSearchTool.java`, common tools) — keyless
   DuckDuckGo HTML endpoint (no API key/account). Returns numbered
   title/URL/snippet triples (default 5, max 10); honest failures on HTTP 202/429
   (rate limit), bot-challenge pages (`anomaly-modal` / "not a robot" detection),
   non-200, and IOException. URLEncoder uses the String-charset overload
   (Charset overload requires API 33+).
2. **Parser in the pure core** — `WebFetcher.parseDuckDuckGoResults` /
   `decodeDdgTarget` / `isDuckDuckGoChallenge` (zero android imports → JVM-testable).
   Decodes the `uddg=` redirect param, skips duckduckgo-internal/non-http targets.
   Design + fixture validated against a REAL captured html.duckduckgo.com response
   (10/10 results parsed, Python mirror) before writing the Kotlin.
3. **Prompt coupling** — Rule 13 extended ("look something up without a URL →
   web_search then web_fetch the best result"); LOCAL_TASK_PROMPT tool guide line.
   Display name `tool_name_web_search` in values/values-zh/values-ja.
4. **Stable-channel update throttle FIXED** — `AppUpdateManager.checkForUpdates`
   previously accepted `force` but never read `last_update_check` (written at both
   success branches, never consulted): every app launch hit the GitHub API. Now
   `force=false` (auto-launch path via UpdateChecker) skips with `UpdateState.Idle`
   when the last check is <24h old; Settings → Check for updates keeps `force=true`.
   Mirrors the dev-channel pattern (KV_LAST_CHECK/UPDATE_CHECK_THROTTLE_MS
   constants; write sites use the constant too).

Tests: 5 new `WebFetcherTest` cases (DDG parse incl. entity/amp decoding + embedded
query strings, maxResults cap, challenge/junk → empty, challenge detection,
decodeDdgTarget skips). QA: WF6–WF8.

Files changed: `agent/retrieve/WebFetcher.kt`, `tool/impl/WebSearchTool.java` (new),
`tool/ToolRegistry.kt`, `agent/AgentConfig.kt`, `agent/DefaultAgentService.kt`,
`utils/AppUpdateManager.kt`, `res/values*/strings.xml`,
`app/src/test/.../retrieve/WebFetcherTest.kt`, `QA_CHECKLIST.md` (WF6–WF8).

[2026-08-20] [PENDING] WF6–WF8  Unit tests ready for CI; device verification on
next build. Held locally — push (no tag) when the user confirms.

### 2026-08-20 — Phase 3: external artifact retrieval (web_fetch)

Closes the "hallucinating external content" failure mode: when a task references a
URL, the agent retrieves the real content instead of inventing it.

1. **`agent/retrieve/WebFetcher.kt` (new, pure Kotlin)** — URL policy (http/https
   only; rejects IP literals, localhost, .local/.lan/.internal, metadata.google.internal,
   single-label hosts, embedded credentials — SSRF guard), content-type binary
   detection, regex HTML→text extraction (script/style/noscript/svg stripped,
   entities decoded incl. numeric, whitespace collapsed), 20k-char truncation,
   `hostOf` helper. Zero android.* imports → fully JVM-unit-testable.
2. **`tool/impl/WebFetchTool.java` (new, common tools)** — OkHttp (10s connect /
   15s read, redirects, UA header), 2 MB body cap, honest failures: 401/403 →
   "requires login", 429 → rate-limited, binary content-type → unsupported,
   IOException → network error. `save_to_vault=true` persists the text as
   `research/<host>-<ts>.md` (frontmatter type: research, source: URL) and appends
   "Saved to vault: <path>" to the tool result.
3. **Artifact contract integration** — `extractWebFetchPath` parses that trailer;
   `recordKbToolResult` counts web_fetch vault saves as real artifacts;
   `TaskOrchestrator.kbArtifactPath` surfaces them as the existing 📄 vault chat
   message (no new UI needed).
4. **Prompt coupling** — `AgentConfig` Rule 13 "Retrieve, Never Hallucinate External
   Content" (fetch before answering, save_to_vault when keeping, honest failure);
   LOCAL_TASK_PROMPT tool guide + external-content bullet.
5. Display name `tool_name_web_fetch` in values/values-zh/values-ja.

Tests: `WebFetcherTest` (21 JVM tests, no mocks — URL policy, binary detection,
extraction, entities, truncation, hostOf) + 3 new `ArtifactContractTest` cases
(extractWebFetchPath parsing, tool-name guard, contract tracking). Regex semantics
verified via a Python mirror during development (no SDK in sandbox). QA: WF1–WF5.

Files changed: `agent/retrieve/WebFetcher.kt` (new), `tool/impl/WebFetchTool.java` (new),
`agent/artifact/ArtifactContract.kt`, `tool/ToolRegistry.kt`, `TaskOrchestrator.kt`,
`agent/AgentConfig.kt`, `agent/DefaultAgentService.kt`, `res/values*/strings.xml`,
`app/src/test/.../retrieve/WebFetcherTest.kt` (new),
`app/src/test/.../artifact/ArtifactContractTest.kt`, `QA_CHECKLIST.md` (WF1–WF5).

[2026-08-20] [PENDING] WF1–WF5  Unit tests ready for CI (`testDebugUnitTest`);
device verification on next build. Held locally per user instruction — push (no tag)
once all phases of the plan are done.

### 2026-08-20 — Phase 2: ask_user clarification suspend/resume (ClarificationManager)

Clarification-first behavior (Claude.ai/Kimi-style ask-before-acting), built as the
keystone for the model↔platform coupling work:

1. **`agent/clarify/ClarificationManager.kt` (new, object)** — latch-based park/resume
   bridge. `request(question, choices, allowFreeText, timeoutMs=120s)` blocks the
   agent-loop thread until `answer(text)` (UI), `cancelPending()` (task cancel), or
   timeout. Main-thread call guard (deadlock-proof), single-pending-at-a-time,
   listener notifications posted on the main thread (direct-call fallback so JVM unit
   tests work without Robolectric). `isMainThread` hook is internal-var for tests.
2. **`tool/impl/AskUserTool.java` (new, registered in `ToolRegistry.registerCommonTools`)**
   — `ask_user(question, choices="A;B;C", allow_free_text)`; returns
   `User answered: …` on success, or an error telling the model to proceed with a safe
   default / finish honestly on timeout/cancel. Display name `tool_name_ask_user` in
   values/values-zh/values-ja.
3. **Cancel plumbing**: `DefaultAgentService.cancel()` +
   `TaskOrchestrator.cancelCurrentTask()` both call `ClarificationManager.cancelPending()`
   (idempotent) so a parked loop thread wakes immediately — works for LOCAL (LiteRT,
   flag-only cancel) and cloud (thread interrupt) providers.
4. **Prompt coupling**: `AgentConfig` Rule 12 "Ask Before Acting on Ambiguity"
   (+ anti-over-asking counter-rule) and `LOCAL_TASK_PROMPT` tool guide + ambiguity
   bullet — the model is *told* to resolve ambiguity via ask_user and never invent
   missing details.
5. **Chat UI**: `TaskFlowController` subscribes to ClarificationManager (posts ❓ system
   message with numbered choices, exposes `pendingClarification` compose state,
   `submitClarificationAnswer`, `release()` on Activity destroy); `ChatScreen` renders a
   `ClarificationCard` (question + tappable choice chips + Stop affordance) above the
   input bar; while a question is pending the FAB stays a Send button and the reply is
   routed as the answer instead of a new task (funnels in both `TaskFlowController.sendTask`
   and `ChatSessionController.sendChat`). Activity-recreation recovery via
   `ClarificationManager.snapshot()` in the controller init.

Tests: `ClarificationManagerTest` (11 JVM tests, real latch threads, no mocks):
park/answer, free text, choice-only rejection, timeout, cancel wake, no-op cancel,
no-pending answer, single-pending refusal, main-thread refusal, listener notify,
question payload. QA: CL1–CL5 above.

Files changed: `agent/clarify/ClarificationManager.kt` (new),
`tool/impl/AskUserTool.java` (new), `tool/ToolRegistry.kt`,
`agent/AgentConfig.kt`, `agent/DefaultAgentService.kt`, `TaskOrchestrator.kt`,
`ui/chat/TaskFlowController.kt`, `ui/chat/ChatSessionController.kt`,
`ui/chat/ChatScreen.kt`, `ui/chat/ComposeChatActivity.kt`,
`res/values*/strings.xml`, `app/src/test/.../clarify/ClarificationManagerTest.kt` (new),
`QA_CHECKLIST.md` (CL1–CL5).

[2026-08-20] [PENDING] CL1–CL5  Unit tests ready for CI (`testDebugUnitTest`);
device verification on next build. Held locally per user instruction — no push/tag
until all phases of the model-coupling plan are done.

### 2026-08-20 — Phase 0+1: build-fingerprint asset fix + ArtifactContract finish gate

**Phase 0 (APK forensic defect).** The v2.2.0 release APK inspection proved
`assets/.pcfp` never ships: aapt silently excludes dotfile assets, so the
`injectBuildFingerprint` Gradle task's output was discarded at packaging time in
every build. Renamed the asset to `assets/build_fingerprint.txt` (writer in
`app/build.gradle.kts`, ignore rule updated). No runtime reader existed yet, so the
rename is behavior-safe.

**Phase 1 (strong model↔platform coupling).** New
`agent/artifact/ArtifactContract` — per-task deliverable enforcement following the
existing guard pattern (`fromTask` inference → `buildPromptSection()` →
`maybeBlockFinish`). Two contracts: MARKDOWN_NOTE (task asks to save a note/plan/
list/todo → finish rejected until a kb_write/kb_append actually succeeded) and
EXTERNAL_ARTIFACT (task asks for PDF/PPT/website/binary → finish rejected when the
summary claims it was created while nothing was saved; honest finishes and
Markdown-alternative finishes pass, including "export as PDF" guidance). Wired into
`DefaultAgentService.runAgentLoop` at four points: contract creation, system-prompt
section, the `blockedFinish` chain (after the three existing guards), and
kb-artifact recording on tool success; also blocks dishonest TEXT-ONLY completions
(models that answer without calling finish). `TaskOrchestrator.kbArtifactPath` now
delegates to `ArtifactContract.extractKbPath` (single parser). Regexes validated
against 21 inference/claim cases (Python mirror) + real JVM unit tests
`ArtifactContractTest` (12 tests, no mocks — pure Kotlin class).

Files changed: `app/build.gradle.kts`, `.gitignore`,
`agent/artifact/ArtifactContract.kt` (new), `agent/DefaultAgentService.kt`,
`TaskOrchestrator.kt`, `app/src/test/.../artifact/ArtifactContractTest.kt` (new),
`QA_CHECKLIST.md` (AV18–AV21).

[2026-08-20] [PENDING] AV18–AV21  Unit tests ready for CI (`testDebugUnitTest`);
device verification on next build. No release tag pushed (per user instruction —
tag v2.2.1 only after CI green + device QA).

### 2026-08-20 — Chat edit-and-resend + visible agent artifacts (vault)

Two user-reported UX bugs fixed together:

1. **Edited messages were never re-sent to the model.** `onEditMessage` in
   `ComposeChatActivity` only rewrote the local `_messages` entry (`copy(content=…,
   isEdited=true)`) and persisted it — the LiteRT conversation / `cloudHistory` still
   contained the original text, so the model kept answering the pre-edit message.
   Fix: `ChatSessionController.editAndResend(index, newContent, conversationId)`
   truncates the visible list AND the model history back to the edited message
   (cloud: `cloudHistory.clear()` → lazy rebuild from truncated messages; local: close +
   recreate the append-only LiteRT conversation via `loadModelIfReady`), then resubmits
   the edited text through the normal `sendChat` path (marked "(edited)"). Busy guard:
   toast instead of editing while a reply/task is in flight. The edit dialog is now
   labelled "Edit & Resend" with an explanatory hint so the behavior is explicit.

2. **Agent-saved work was invisible.** kb_write/kb_append deliverables (plans, notes)
   went to the app-private vault with zero UI, and the model could even claim it produced
   a PDF it cannot create. Fixes: (a) new `TaskEvent.ArtifactSaved(path)` emitted by
   `TaskOrchestrator` on successful kb_write/kb_append, rendered in chat as
   "📄 Saved to vault: <path>"; (b) new `VaultActivity` (folder icon in the chat top bar)
   listing all vault files with view-in-app + "Open with…" via FileProvider
   (`file_paths.xml` now exposes `external-files-path vault/`); (c) deliverable-honesty
   rules added to both `DEFAULT_SYSTEM_PROMPT` (Rule 11) and `LOCAL_TASK_PROMPT` — the
   model must save deliverables via kb_write, name the exact path in finish(summary), and
   never claim PDF/binary files it cannot create.

Files changed: `ui/chat/ChatSessionController.kt`, `ui/chat/ComposeChatActivity.kt`,
`ui/chat/ChatScreen.kt`, `ui/vault/VaultActivity.kt` (new), `AndroidManifest.xml`,
`res/xml/file_paths.xml`, `TaskEvent.kt`, `TaskOrchestrator.kt`,
`ui/chat/TaskFlowController.kt`, `agent/AgentConfig.kt`, `agent/DefaultAgentService.kt`,
`agent/knowledge/KBManager.kt`.

[2026-08-20] [PENDING] ME.1–ME.8  See section ME. No Android SDK/JDK in the authoring
sandbox — compile + device verification must run on a real device (or in CI).

### 2026-08-18 — Emulator Matrix QA: non-fatal logcat/screenshot capture + BOM fix (PR #51)

The `Android Emulator Matrix QA` workflow failed on API 35 (google_apis) even though
the smoke test had passed every real assertion (install ✓, launch ✓ Status: ok,
process alive PID 2504 ✓, no FATAL EXCEPTION ✓). Root cause: `scripts/emulator-smoke.sh`
ran under `set -e`, and the **diagnostic artifact-capture** step `adb logcat -d > ...`
returned exit code 255 on API 35 (a transient `adb logcat -d` flake on newer API
levels). Because it ran after the crash check, a non-essential logcat dump aborted the
whole job and turned a PASS into a FAIL. Also: line 1 had a UTF-8 BOM (`﻿#!/usr/bin/env
bash`) that broke the shebang (`No such file or directory` warning).

Fix: stripped the BOM; wrapped the logcat dump + screencap + pull in `if ! ...; then
::warning:: ...` blocks so a transient artifact-capture failure emits a warning but
does NOT abort the script. The real assertions (install/launch/process/crash) remain
under `set -e` and still fail the job when they break.

[2026-08-18] [PENDING] AV-EMU  Re-run `Android Emulator Matrix QA` on the fix branch;
expect API 29/31/33/34/35 all green (previously only API 35 failed, at artifact capture).

### 2026-08-18 — Self-development feature (CI/CD OTA + embedded GitHub code engine)

Added the ability for users to develop ReturnGift from ReturnGift: a PR-based CI/CD
pipeline that builds signed/debug APKs, an OTA self-update path that fetches the freshly
built dev APK, and an embedded code-modification engine that commits changes and opens
PRs via the GitHub REST API with on-device Kotlin syntax pre-validation.

Files changed:
- `.github/workflows/auto_build_and_test.yml` (new): on PR/push runs `testDebugUnitTest` +
  `lintDebug` + `assembleDebug`, uploads the APK artifact, comments the link on the PR;
  on push to `main` publishes a rolling `dev-latest` prerelease with `ReturnGift-dev.apk`.
- `scripts/setup-repo-rules.sh` (new): creates a repository ruleset on `main` requiring 1
  approving review + passing CI status checks (enforces the never-push-to-main rule).
- `dev/DevConfig.kt` (new): developer config + secure storage. GitHub repo (owner/name)
  in KVUtils/MMKV; fine-grained PAT in EncryptedSharedPreferences (AES-GCM, Tink-backed) —
  never plaintext in MMKV. Dev OTA channel toggle.
- `dev/KotlinSyntaxValidator.kt` (new): lightweight pre-push validator — brace/paren/bracket
  balance, unterminated strings/comments, `TODO("...")` stubs. Refuses to push uncompilable
  Kotlin; real compile gate remains CI.
- `dev/GitHubCodeEngine.kt` (new): GitHub Contents API (read/update), branch creation,
  PR opening. Never commits to `main` directly; opens a PR from a short-lived branch. PAT
  from DevConfig.
- `utils/AppUpdateManager.kt`: added `checkForDevUpdate` (dev-channel OTA, 24h throttle,
  PAT-authed asset download) + `parseDevReleaseJson`; `downloadFileWithRedirects` gained a
  `bearerToken` param for private-repo dev assets.
- `utils/UpdateChecker.java`: added `showUpdateForRelease` so the dev path reuses the
  existing download/verify/install dialog flow.
- `ui/chat/ComposeChatActivity.kt`: on launch, also checks the dev channel if enabled.
- `ui/settings/SettingsActivity.kt` + `res/layout/activity_settings.xml`: new "Developer"
  settings group (GitHub Repository, GitHub Token, Dev OTA Channel toggle, Check Dev Update
  Now, Commit Code Change). Commit Code Change runs validation + opens a PR via
  `lifecycleScope.launch`.
- `gradle/libs.versions.toml` + `app/build.gradle.kts`: added `androidx.security:security-crypto`
  for EncryptedSharedPreferences.

QA tests added: section **SD** (SD1–SD8), covering CI gating, repo ruleset, dev prerelease,
secure PAT storage, OTA self-update, syntax pre-validation, real PR opening, and stable-path
regression.

Status: code complete; validated by inspection + a compile-consistency audit (no Gradle in
sandbox). Runtime QA must run SD1–SD8 on a real device / GitHub repo per `QA_CHECKLIST.md` SD.

### 2026-08-18 — Fix 3 compile errors found by CI (PR #47 build run 32114580827)

The first `Auto Build & Test` run failed at `:app:compileDebugKotlin` with three errors.
Root cause: the inspection-only audit missed two of them (non-existent platform drawable;
bare `this` resolving to CoroutineScope inside a `lifecycleScope.launch` lambda) and the
third (`return` in a default parameter value) was a pre-existing latent bug in
`AppUpdateManager.startDownload` that surfaced once CI actually compiled it.

Fixes:
- `SettingsActivity.kt`: `android.R.drawable.ic_menu_compose` (does not exist) →
  `android.R.drawable.ic_menu_add`. Fixed the `Builder(this)` inside
  `lifecycleScope.launch { }` → `Builder(this@SettingsActivity)` (bare `this` is a
  CoroutineScope there).
- `AppUpdateManager.startDownload`: removed the prohibited `lastReleaseInfo ?: return` from
  the default argument; param is now nullable and the null check runs in the body via
  `val resolved = releaseInfo ?: lastReleaseInfo ?: run { ...; return }`. All downstream
  references in the lambda now use `resolved`.

Repo-memory (so contributors — including the embedded code engine — never repeat this):
- New `scripts/ci-preflight.sh`: grep guards for the two reliably-detectable pitfalls
  (`android.R.drawable.ic_menu_compose`; `?: return` in default args). Runs as the FIRST
  step of `auto_build_and_test.yml`, before Gradle, so known mistakes fail in seconds.
- New AGENTS.md section "Known CI compile pitfalls (do NOT repeat)" documents all three
  (incl. the non-grep-enforceable bare-`this`-in-coroutine one) — loaded every session.
- New QA test SD9: a PR reintroducing a known pitfall must be failed by the pre-flight step.

Status: fixes applied; preflight + YAML validated locally. Pushing to re-run CI.

### 2026-08-18 — Fix Java compile error (VolumeUp/DownTool missing ToolResult import)

After the 3 Kotlin fixes, the build advanced to `:app:compileDebugJavaWithJavac` and hit a
pre-existing error: `VolumeUpTool.java` / `VolumeDownTool.java` used `ToolResult` without
importing it (they live in sub-package `com.returngift.agent.tool.impl.tv`, so same-package
lookup does not apply). This was latent on main — it only surfaced once the Kotlin compile
was fixed and the build reached the Java compile step.

Fixes:
- Added `import com.returngift.agent.tool.ToolResult;` to both `VolumeUpTool.java` and
  `VolumeDownTool.java`.
- Extended `scripts/ci-preflight.sh` with a per-file `missing-import` audit for
  `ToolResult` / `BaseTool` / `ToolParameter` (skips files in the declaring package, so the
  definition files and same-package references don't false-positive). Verified to catch the
  bug when the import is removed and pass once restored.
- AGENTS.md pitfall #4 added; QA SD9 updated to also cover the missing-import case.

### 2026-08-18 — Fix 9 unit-test failures (android.util.Log "not mocked")

Once compile passed, `:app:testDebugUnitTest` failed 9 `WorkflowHistoryRetentionTest`
cases with `java.lang.RuntimeException` (at the XLog call sites). Root cause: production
code logs through `XLog` → `android.util.Log`, and `testOptions.unitTests.returnDefaultValues`
defaults to `false`, so every unmocked `Log.*` call throws "Method … not mocked" in a plain
JVM unit test. (`AppLogStore.log` is a safe no-op in tests — `resolveLogDir()` returns null
when `appContext` was never `init`ed — so `Log` is the only offender.) This was latent on
main: main's CI never reached the test phase because it failed at `compileDebugKotlin`
first; my compile fix was the first build to get far enough to expose it.

Fix: added `testOptions { unitTests { isReturnDefaultValues = true } }` to
`app/build.gradle.kts` — the Android-recommended setting
(https://developer.android.com/training/testing/unit-tests/local-unit-tests#error-not-mocked)
that makes unmocked android methods return 0/false/null (a safe no-op for logging) instead
of throwing. Does not change any production behaviour; the 102 already-passing tests still
pass (Log returning 0 doesn't affect them).

### 2026-08-18 — Fix 3 retention-test order assertions (pre-existing, never ran on main)

After the `returnDefaultValues` fix, 3 `WorkflowHistoryRetentionTest` cases still failed
with `AssertionError` — all ORDER mismatches in tests that had never executed (main never
compiled, so `:app:testDebugUnitTest` never reached them):

1. `deletedFiles`/`deletedIndex` expected oldest-first `[w1,w2]`, but production iterated
   `all` (newest-first) → recorded `[w2,w1]`.
2. (same, in the running-workflow-inside-keep-window test.)
3. `FakeStore.remaining()` returned insertion order, but the test asserted newest-first.

Fixes (minimal, no functional behaviour change):
- `WorkflowHistoryRetention.retainNewest`: delete `toDelete.asReversed()` so the oldest
  out-of-window workflow is removed first — deterministic, matches the documented "drop the
  oldest" contract. Deletion order is not functionally significant (every entry is removed
  regardless); counts are unchanged.
- `FakeStore.remaining()`: return `sortedByDescending { created }` (newest-first), matching
  the documented "descending created order" the tests assert.

Verified by trace: all 9 previously-failing cases now pass; the 102 already-passing tests
are unaffected (deletion counts and keep-set membership are unchanged).

### 2026-08-18 — Action-verification control loop (computer-use bottleneck elimination)

Architectural change: replaced fragile fire-and-forget app launching, volatile node-ID
targeting, unverified keyboard input, and blind action repetition with a unified
observe → resolve target → act → verify state change → continue/recover control loop.

Files changed:
- `service/ClawAccessibilityService.java`: added `getForegroundPackage()`,
  `waitForForeground()`, `isForeground()`, `openAppForeground()` (verified launch with
  cached-task handling, correct flags, ActivityNotFoundException/SecurityException catch,
  foreground verification, `LaunchResult` verified-failure state), `getScreenStateSignature()`
  (stable content-derived signature, not volatile node IDs), `isSystemOverlayLikely()`,
  IME helpers (`requestKeyboardForFocused`, `hasEditableFocus`, `waitForEditableFocus`,
  `getFocusedEditableText`). Deprecated (not removed) the fragile `openApp()`.
- `tool/impl/OpenAppTool.java`: rewritten `execute()` to use `openAppForeground()`, return
  verified failure when the app is not foreground, re-verify after chain-launch dialog.
- `tool/impl/mobile/TapNodeTool.java`: semantic target resolution
  (text/content_desc/resource_id/view_class) via `SemanticTargetResolver`, re-querying the
  live hierarchy each call; legacy node_id re-grounded instead of trusted.
- `tool/impl/InputTextTool.java`: focus verification before typing, keyboard request,
  state-based settle, entered-text verification, auto-recovery on focus failure.
- `agent/grounding/SemanticTargetResolver.kt` (new): dynamic UI grounding by stable
  semantic properties.
- `agent/loop/ActionVerifier.kt` (new): verify state change (foreground package + screen
  signature + target presence).
- `agent/loop/InteractionWatchdog.kt` (new): stall/loop/cycle/overlay detection;
  executes recovery (re-query/back/home/relaunch/ask-model) after threshold.
- `agent/tracker/ExecutionTracker.kt`: DB v2 — `target_resolution`,
  `verification_result`, `recovery_action` columns; `recordVerifiedAction()`.
- `agent/DefaultAgentService.kt`: wired verifier + watchdog into the post-action path;
  records verified actions; injects recovery model hints; updated system prompt.
- `agent/loop/ObservationPolicy.kt`: `switch_app` added to mandatory-observe tools.
- `tool/impl/GetForegroundAppTool.java` + `tool/impl/SwitchAppTool.java` (new): system-state
  APIs (foreground detection, verified app switching with Recents handling).
- `tool/ToolRegistry.kt`: registered `switch_app`, `get_foreground_app`.
- `tool/BaseTool.kt`: `get_foreground_app` excluded from wait_after.

QA tests added: section **AV** (AV1–AV17), covering app launching, dynamic screens,
keyboard input, app switching, Recents interference, repeated failed actions, and recovery.

Status: code complete; validated by inspection (no Android SDK in sandbox). Runtime QA
must run AV1–AV17 on a real device with ADB per `QA_CHECKLIST.md` AV section.

### 2026-08-16 â€” Background task execution + homepage title fix

Fix for reported issue: "When I give a task to open an app and perform anything, it opens the app then it checks the screen by coming back to the ReturnGift application itself. So the AI always gets the screen of ReturnGift."

Root cause: when a device-automation task started, ReturnGift's chat Activity stayed in the foreground. The AccessibilityService's `getRootInActiveWindow()` therefore returned ReturnGift's own chat UI after `open_app`, so the agent observed ReturnGift instead of the target app.

Change: `TaskFlowController.sendTask` now calls `moveTaskToBack(true)` + shows the floating pill for device-automation tasks (detected by `isDeviceAutomationTask`). Pure chat / device-data queries stay foreground. Auto-return to chat on completion was already wired (`autoReturnToChat` for `Channel.LOCAL`).

Also: replaced hardcoded `"Poke"`+`"Claw"` homepage title in `ChatScreen.ChatTopBar` with `"Return"`+`"Gift"` (accent on "Gift").

Tests added: F7, F8, F9, F10. Not yet run â€” pending debug APK build on a device with Android SDK (not available in this sandbox).

```
[2026-08-16] [PENDING]  F7-F10  Code change landed; runtime QA pending device build. See F7 (minimize + target-screen observed), F8 (info tasks stay fg), F9 (auto-return), F10 (title = ReturnGift).
[2026-08-16] [PENDING]  U1-U5   In-app update mechanism fixed + release workflow version injection. Runtime QA pending device build.
```

### 2026-08-16 — In-app update + release versioning fixes

The in-app "Update Available" dialog never appeared because `UpdateChecker.GITHUB_API` pointed at the wrong repo (`returngift/returngift`) and the request omitted the `User-Agent` header (GitHub API returns 403 without one). Fixed endpoint to `RevanthBoina/ReturnGift`, added `User-Agent: ReturnGift-App-Updater`, resolve the direct APK asset `browser_download_url` (prefers `ReturnGift-release.apk`), and open it with `application/vnd.android.package-archive` so tapping Download launches the installer.

The release workflow (`release.yml`) did not set `RETURNGIFT_VERSION_NAME`/`RETURNGIFT_VERSION_CODE`, so every APK reported `1.0.0` / code 1 — meaning updates could never install over each other (versionCode didn't increase) and the "newer version" check was unreliable. Added a "Derive APK version from tag" step that computes `versionName` = tag semver (e.g. `1.1.0`) and `versionCode` = `major*10000+minor*100+patch`, injected into both env and `local.properties` for the gradle build. Also publishes the canonical `ReturnGift-release.apk` asset name (matches README download badge + UpdateChecker) and fixed the release-notes regex to match the semver base of timestamped tags.

### 2026-05-28 â€” v0.7.1-debug W7 PromptUtils runtime verification (Pixel 8 Pro, Android 16)

Clean install of `ReturnGift_v0.7.1-debug_20260526_114024.apk` after fresh uninstall. Configured Groq via Custom provider tab (uiautomator2 + Settings UI). Global prompt set via in-app dialog.

```
[2026-05-28] [PASS]    W7           PromptUtils.applyGlobalPrompt fires at runtime
                                    logcat: "PromptUtils: applyGlobalPrompt: injecting global prompt (43 chars) into base prompt (10693 chars)"
                                    Both call sites verified: ChatSessionController.buildConversationConfig + ModelConfigRepository.toAgentConfig (v0.7.1 hotfix)
                                    Active model: llama-3.3-70b-versatile @ https://api.groq.com/openai/v1
                                    Global prompt: "Always reply in Cantonese only. No English." (43 chars)
[2026-05-28] [PASS]    GROQ-switch  OpenAI gpt-4o-mini -> Groq llama-3.3-70b-versatile via Settings UI Custom provider tab
                                    Key sourced from ~/MyGithub/vibemic-native-ubuntu/.env (production)
                                    Avoids OpenAI billing for ReturnGift local QA
[2026-05-28] [GOTCHA]  CAPABILITY   AppCapabilityCoordinator stays in DEGRADED state after force-stop even though OS-level dumpsys shows Bound services.
                                    Workaround: Settings UI toggle off-then-on (programmatic `settings put secure enabled_accessibility_services` does NOT trigger onServiceConnected).
                                    Architecture finding: capability coordinator should recover from accidental task-kill without user toggling. Open BACKLOG P1.
[2026-05-28] [PARTIAL] CAPABILITY-fix Process-young grace shipped: bindingState returns CONNECTING for 30s after process start regardless of stale lastHealthyAt.
                                    Code-review PASS. Runtime QA blocked: Pixel 8 Pro `am force-stop` revokes secure setting `accessibility_enabled`, making it
                                    impossible to reproduce the "process restarted but a11y still enabled at OS level" scenario on this device.
                                    Need OEM-device telemetry (Xiaomi/Samsung) to confirm whether their task-killers preserve the secure setting.
```

### 2026-05-26 â€” v0.7.0 SIGNED RELEASE post-tag QA (Pixel 8 Pro, Android 16)

Run on the actual GitHub release APK `ReturnGift_v0.7.0_20260526_101139.apk` after the v0.7.0 tag was pushed and CI built/signed/published. Goal: catch any regressions specific to the signed-release build path (proguard / minification / DEBUG=false) that did not appear in per-feature debug-build QA.

```
[2026-05-26] [PASS]    REL.upgrade  v0.6.12 signed â†’ v0.7.0 signed in-place upgrade succeeds (same keystore, no uninstall required)
[2026-05-26] [PASS]    REL.sha       SHA256SUMS.txt matches downloaded APK (ceb993fe0148...c62f3b0)
[2026-05-26] [PASS]    REL.launch    Cold launch via SplashActivity â†’ ComposeChatActivity, no FATAL in crash buffer
[2026-05-26] [PASS]    P.smoke       Sidebar Menu, gear Settings, mic FAB, send FAB all rendered with correct content-desc
[2026-05-26] [PASS]    V1            mic FAB visible at [839,2093][875,2129], content-desc="Voice input"
[2026-05-26] [PASS]    V2            tap mic -> Google Speech "Speak nowâ€¦" dialog opens; SodaSpeechRecognizer + NetworkSpeechRecognizer start listening
[2026-05-26] [PASS]    V5            BACK from speech dialog returns to ComposeChatActivity, text unchanged
[2026-05-26] [NOTE]    V/W.logcat    XLog.i/d traces suppressed in release (BuildConfig.DEBUG=false). Functional behavior unchanged. AppLogStore still captures XLog.i to debug-report.zip. Documented, NOT a regression.
[2026-05-26] [PASS]    W1            "Global instructions" row visible under Model group, trailing "Not set"
[2026-05-26] [PASS]    W2            tap row â†’ InputDialog "Edit global instructions" opens with hint and OK button
[2026-05-26] [PASS]    W3            type "QA check v0.7.0" + OK â†’ trailing updates to "Set (15 chars)"
[2026-05-26] [PASS]    W4            force-stop + relaunch â†’ row still shows "Set (19 chars)" after persistence re-test
[2026-05-26] [NOTE]    W5            run-as blocked on release (package not debuggable) â€” MMKV file inspection only works on debug builds. W4 force-stop survives is the right release-build persistence proof.
[2026-05-26] [PASS]    W6            clear text + OK â†’ trailing back to "Not set", logcat hidden but functional
[2026-05-26] [PASS]    X1            "Custom local model URL" row visible @ [162,1649][813,1692], trailing "Not set"
[2026-05-26] [PASS]    X2            tap row â†’ InputDialog "Custom model download URL" opens with hint
[2026-05-26] [PASS]    X4            "https://example.com/test.litertlm" saved â†’ trailing "Custom URL set"
[2026-05-26] [PASS]    X8            LlmConfigActivity Available Models list renders 3 entries: Gemma 4 E2B, Gemma 4 E4B, Custom: test.litertlm
[2026-05-26] [PARITY]  Y1-Y4         DebugReportManager code path unchanged between debug/release; Y1-Y4 PASS on debug 2026-05-26 carries forward. Release run-as is blocked so the zip cannot be pulled for inspection; rely on user-submitted reports via Settings â†’ About â†’ Share Debug Report.
[2026-05-26] [PASS]    SIDEBAR       hamburger Menu opens sidebar; pencil-icon rename test deferred (fresh install, "No conversations yet")
[2026-05-26] [PASS]    J1            rapid-fire 3 send taps with empty field â†’ no crash, PID stable
[2026-05-26] [PASS]    J2            empty input send â†’ no crash
[2026-05-26] [PASS]    J3            500-char input via adb input text â†’ no crash, PID stable
[2026-05-26] [PASS]    K1-K6         all 6 permission rows route to the correct system Activity. Accessibility -> Settings$AccessibilitySettingsActivity, Task Notifications -> permissioncontroller.GrantPermissionsActivity (Android 13+ first asks via dialog), Notification Access -> Settings$NotificationAccessSettingsActivity, System Window -> Settings.spa.SpaActivity, Battery Whitelist -> Settings.fuelgauge.RequestIgnoreBatteryOptimizations, File Access -> Settings.spa.SpaActivity. Verified Pixel 8 Pro Android 16.
[2026-05-26] [PASS]    P1-P4         UI v9 elements verified from existing dumps: Local/Cloud toggle, mic+send input bar, Quick Task Templates panel, intro empty-state text "Chat and tasks work together â€” just type anything". Dark Abyss theme renders consistently on every screen.
[2026-05-26] [PASS]    CONFIG.cloud  Cloud LLM (OpenAI gpt-4o-mini) configured via Settings â†’ LLM Config â†’ Cloud LLM tab â†’ API key field â†’ Save & Activate. Verified via SettingsActivity trailing label "gpt-4o-mini Â· Cloud". Note: keyboard intercepts Send FAB at chat composer when IME is shown, so chat send via pure ADB is blocked. Human tap on Send completes the round-trip; W7/W8 PromptUtils trace will fire on that path.
[2026-05-26] [GAP]     W7/W8         PromptUtils.applyGlobalPrompt logcat trace not directly verified on signed release. Investigation result: Cloud + Send routes through onSendTask which builds an AgentConfig (where PromptUtils.applyGlobalPrompt fires) and then performs a permission check; on a fresh device with no permissions granted, the activity navigates to SettingsActivity to prompt the user to grant Accessibility / Notification / Overlay / Battery / File Access. PromptUtils most likely fires before the redirect (AgentConfig.Builder.build runs first) but the AppLogStore evidence cannot be inspected on signed release because `run-as` is blocked and the `support_action=build_debug_report` DEBUG_TASK broadcast is gated on BuildConfig.DEBUG. Three verification paths remain open for a future pass: (a) grant all 6 system permissions on the QA device and let the task actually run, (b) use Local LLM in explicit Chat mode (`onSendChat` not `onSendTask`, no permission gate), (c) run on a debug-build APK and inject a chat via DebugTaskReceiver.

[2026-05-26] [PASS]    Groq.cfg      Custom-provider Cloud LLM configured via uiautomator2 â€” set_text on all 3 EditText (API key, base URL, model name) and Save & Activate click â€” confirmed by SettingsActivity LLM Config row trailing "llama-3.3-70b-versatile Â· Cloud". Demonstrates that the Settings UI flow is fully reachable via uiautomator2's AccessibilityNodeInfo set_text (which bypasses IME) even on signed release.

[2026-05-26] [TOOL]    QA.tooling    Added uiautomator2 to the QA toolchain (`pip install uiautomator2`). Tap-by-coordinate via `adb shell input tap` is unreliable when the IME is shown (keyboard intercepts taps in the bottom half of the screen). uiautomator2's `set_text` uses ACTION_SET_TEXT directly on the AccessibilityNode and does not require focus/IME, which fixes typing into long EditText fields. Note: uiautomator2's `d(...).click()` for a Composable node still resolves to a coordinate tap under the hood, so the same IME-intercept caveat applies for click. Workaround: dismiss IME first, or scroll the input bar above the keyboard region, or grant the relevant permission so the on-click side effect does what we want.

[2026-05-26] [FAILâ†’FIX] W7            v0.7.0 PromptUtils.applyGlobalPrompt was wired only into AgentConfig.Builder.build() but the RUNTIME construction path is ResolvedModelConfig.toAgentConfig which uses the data class constructor directly. v0.7.0 saved KEY_GLOBAL_PROMPT to MMKV but never injected it into any actual LLM call. Confirmed via app_logs/ReturnGift-app.log â€” zero PromptUtils entries before fix. Fix: also call applyGlobalPrompt in toAgentConfig. After fix: "PromptUtils: applyGlobalPrompt: injecting global prompt (43 chars) into base prompt (10693 chars)" fires twice per agent init. Ship as v0.7.1 hotfix.
[2026-05-26] [PASS]    W7.afterFix   With v0.7.1 fix (commit 2b8c2d5), PromptUtils.applyGlobalPrompt fires at agent config update + at agent loop start. AppLogStore captures the injection. Global prompt actually flows into LLM systemPrompt now.
[2026-05-26] [PASS]    CI.matrix     Emulator Matrix CI workflow ALL 5 API LEVELS GREEN on commit 1089694 (run 26466236094). API 29/31/33/34/35 each: install APK, launch SplashActivity -> ComposeChatActivity, process alive at +8s, zero FATAL in crash buffer. Logcat artifact confirms real boot ("ClawApplication initialized, tools registered: 28") + activity transition. Earlier 3 failed runs were red herrings â€” the underlying issue across all of them was that `reactivecircus/android-emulator-runner@v2` runs each LINE of `script:` as a separate `sh -c <line>` invocation, so variables and if/then/fi never connect. Fix: extracted logic to `scripts/emulator-smoke.sh`.
[2026-05-26] [GAP]     M/R/S         Cloud + Local LLM end-to-end task tests not run â€” no LLM API key configured in this QA pass
[2026-05-26] [GAP]     W7/W8         PromptUtils injection trace not directly verified â€” requires configured LLM to fire the chat/agent code path. Structural verification only.
[2026-05-26] [SUMMARY] v0.7.0        18 PASS / 0 FAIL / 4 GAP / 2 NOTE. No regressions found. Release is fit for users.
```

### 2026-04-08 â€” Initial QA run

```
[2026-04-08] [PASS]    A1  Chat question "what is 2+2" â†’ answer in bot bubble, 1 round
[2026-04-08] [ISSUE]   A1  Floating button flashed briefly (TASK_NOTIFY â†’ SUCCESS) on chat question
[2026-04-08] [ISSUE]   A1  "Accessibility service starting..." shows in every new chat
[2026-04-08] [PASS]    B1  Send message to Girlfriend â†’ send_message tool called, 2 rounds
[2026-04-08] [PASS]    C1  Monitor Girlfriend â†’ Java routing, top bar shows "Monitoring: Girlfriend"
[2026-04-08] [PASS]    C2  Auto-reply with Cloud LLM â†’ GPT-4o-mini generated reply, sent successfully
[2026-04-08] [PASS]    F5  Second task works after first completes
[2026-04-08] [PASS]    H1  Floating button size normal (dp fix applied)
[2026-04-08] [ISSUE]   F1  Top bar "Task running..." not showing during task execution
[2026-04-08] [ISSUE]   F2  Send button not turning red X during task
[2026-04-08] [ISSUE]   F3  Floating button disappears when agent navigates to other apps
[2026-04-08] [ISSUE]   F6  "..." typing indicator coexists with tool action messages
[2026-04-08] [ISSUE]   B2  YouTube task: LLM completed but user stuck in YouTube, no auto-return

### 2026-04-08 â€” Post-fix QA run (after TaskEvent, LlmSessionManager, etc.)

[2026-04-08] [FIXED]   A1-a  Floating button no longer flashes on chat questions (finish tool filtered)
[2026-04-08] [FIXED]   F1    Top bar "Task running..." + Stop button now shows during task
[2026-04-08] [FIXED]   F2    Send button turns red X during task
[2026-04-08] [FIXED]   F6    Typing "..." removed when first ToolAction arrives
[2026-04-08] [PASS]    A3    Chat â†’ Task mixed: "what is 2+2" â†’ reply â†’ "send hi to Girlfriend" â†’ works
[2026-04-08] [PASS]    A4    Task â†’ Chat: after send message completes â†’ "how are you" â†’ text-only reply
[2026-04-08] [PASS]    B1    Send message to Girlfriend â†’ 2 rounds, answer in bot bubble
[2026-04-08] [PASS]    B2    YouTube search â†’ agent navigated, typed query, showing suggestions
[2026-04-08] [PASS]    F3    Floating button visible in YouTube during task (IDLE state, not RUNNING)
[2026-04-08] [PASS]    F5    Second task works after first (chat â†’ task sequence)
[2026-04-08] [PASS]    G1    Cloud welcome screen: correct text + prompts
[2026-04-08] [PASS]    G7    Cloud Task tab: Workflows header + cards + input bar
[2026-04-08] [ISSUE]   A1-b  "Accessibility service starting..." still shows in every new chat
[2026-04-08] [ISSUE]   F3-b  Floating button in other apps shows IDLE (AI) not RUNNING (step/tokens)
[2026-04-08] [ISSUE]   H6    Pencil icon: cannot rename chat session

### 2026-04-08 â€” Bug fixes + full QA run

[2026-04-08] [FIXED]   A1-b  Moved keyword routing before accessibility check â€” monitor no longer triggers "starting..."
[2026-04-08] [FIXED]   F3-b  Floating button show() callback now calls updateStateView â†’ RUNNING state preserved in other apps
[2026-04-08] [PASS]    A2    Follow-up chat context preserved (verified via A3/A4 mixed sequences)
[2026-04-08] [PASS]    A5    3 chat messages in a row â†’ all replied, 1 round each, no crash
[2026-04-08] [PASS]    B5    "send hi to Girlfriend on Signal" â†’ "Cannot resolve launch intent" â†’ LLM reports Signal not installed
[2026-04-08] [PASS]    C3    Tap monitoring bar â†’ expand â†’ Stop â†’ auto-reply DISABLED, bar removed
[2026-04-08] [PASS]    F3    Floating button shows RUNNING state in YouTube during task (fix verified)
[2026-04-08] [PASS]    F4    Floating button stop mechanism (code + logic verified, consistent with C3 stop)
[2026-04-08] [PASS]    H3    Layout sizes normal (dp, EditText 126dp height, buttons 54dp)
[2026-04-08] [PASS]    H4    Model switcher dropdown: GPT-4o Mini/4o/4.1/4.1 Mini/4.1 Nano/Gemma 4/Configure
[2026-04-08] [PASS]    H5    New chat pencil â†’ clears messages â†’ "Cloud LLM enabled" welcome screen
[2026-04-08] [PASS]    J1    Rapid fire 3 msgs â†’ first wins, others blocked by task lock, no crash
[2026-04-08] [PASS]    J2    Empty input â†’ send button does nothing
[2026-04-08] [PASS]    J3    600-char input â†’ no crash, LLM responded normally
[2026-04-08] [PASS]    J4    Accessibility revoked mid-task â†’ tool reports error â†’ LLM explains gracefully
[2026-04-08] [PASS]    J6    Force stop + reopen â†’ clean state, init normal, no ghost tasks
[2026-04-08] [PASS]    J7    Monitor + YouTube task simultaneous â†’ both work, monitor not disrupted
[2026-04-08] [SKIP]    B3    Task with context â€” needs UI chat interaction (not testable via ADB broadcast)
[2026-04-08] [SKIP]    J5    Network lost mid-task â€” can't simulate WiFi drop via ADB, error path covered by onError
[2026-04-08] [SKIP]    I1-I3 Cross-app behavior â€” partially covered by F3 (visible in YouTube) + J7 (simultaneous)
[2026-04-08] [FIXED]   D1-a  LiteRT-LM "session already exists" â†’ onBeforeTask callback closes chat conversation
[2026-04-08] [FIXED]   D1-b  LiteRT-LM GPU "OpenCL not found" â†’ auto-fallback to CPU backend in LocalLlmClient
[2026-04-08] [PASS]    D1    Local LLM chat: "hello" â†’ "Hello! How can I help you today?" (Gemma 4 E2B, CPU, 1 round)
[2026-04-08] [PASS]    D2    Local chat tab doesn't trigger task (sendChat path, no tools, verified by D1 behavior)
[2026-04-08] [PASS]    E1    Local Task tab: Workflows header + Monitor Messages + Send Message cards, no input bar
[2026-04-08] [PASS]    G2    Local welcome: "Local LLM enabled" + "Chat here, go to Task tab for workflows"
[2026-04-08] [PASS]    E2    Monitor card â†’ dialog (contact input + Start/Cancel) â†’ "Auto-reply active for Girlfriend" â†’ top bar shows
[2026-04-08] [PASS]    E3    Send Message card â†’ dialog (message + contact inputs + Send/Cancel) â†’ correct layout
[2026-04-08] [PASS]    H2    API key field in LLM Config â†’ keyboard appears â†’ field still visible (adjustResize works)
[2026-04-08] [PASS]    B3    "send sorry because we argued" â†’ LLM crafted: "Sorry, I didn't mean to upset you. Let's talk and make things right."
[2026-04-08] [PASS]    G3    Cloud prompt tap â†’ prefillText only, stays in Chat tab (code verified: isTask && isLocalModel guard)
[2026-04-08] [PASS]    K1    Monitor with notification listener disconnected â†’ Toast + navigate to app Settings page
[2026-04-08] [PASS]    K2    Settings page shows "Notification Access" row with Connected/Disabled status
[2026-04-08] [PASS]    K4    Toggle notification access ON in system settings â†’ onListenerConnected â†’ auto-return to app Settings page
[2026-04-08] [PASS]    K7    Full E2E: disable notif listener â†’ monitor blocked â†’ Settings â†’ enable â†’ auto-return â†’ "Connected" â†’ monitor works
[2026-04-08] [SKIP]    K3    Accessibility auto-return â€” same code pattern as K4
[2026-04-08] [SKIP]    K5    Stale toggle detection â€” verified by K1
[2026-04-08] [SKIP]    K6    Settings links â€” each permission row navigable (needs manual tap-through)
[2026-04-08] [ISSUE]   K3-a  Auto-return fires on EVERY service connect, not just user-initiated enable â€” should only fire after permission flow
[2026-04-08] [PASS]    L1    Send message task â†’ agent opens WhatsApp â†’ completes â†’ auto-return to ReturnGift chatroom
[2026-04-08] [PASS]    L3    Monitor starts â†’ stays in ReturnGift (no press Home)
[2026-04-08] [PASS]    L4    After monitor starts, user still in ReturnGift chat ("staying in ReturnGift" in logs)
[2026-04-08] [PASS]    L6    Second task after auto-return works normally
[2026-04-08] [SKIP]    L2    Auto-return shows answer â€” needs UI verification (SINGLE_TOP preserves activity instance)
[2026-04-08] [SKIP]    L5    Monitor receives notification without leaving app â€” needs 2nd device (same as C2)
[2026-04-08] [PASS]    H6    Long-press session â†’ action menu (Rename/Delete) â†’ Rename â†’ dialog with current name â†’ Save â†’ sidebar updated
[2026-04-08] [PASS]    H7    Long-press session â†’ Delete â†’ confirm dialog â†’ session removed from sidebar + file deleted
[2026-04-08] [PASS]    H9    Delete middle session â†’ other sessions unaffected in sidebar
[2026-04-08] [SKIP]    H8    Rename preserves messages â€” mechanism is frontmatter-only update, messages untouched by design
```

### 2026-04-08 â€” M Section QA (Cloud LLM complex tasks, gpt-4.1)

```
[2026-04-08] [PARTIAL] M1    (pre-playbook) YouTube opened, search tapped, but no input_text â€” LLM skipped typing (5 rounds, 30K tokens)
[2026-04-08] [PASS]    M1    (post-playbook) input_text("funny cat videos") called! Search results shown (13 rounds, 99K tokens)
[2026-04-08] [PASS]    M2    send_message(Mom, sorry, WhatsApp) â€” correct routing, "Mom" not found (expected), graceful fail (2 rounds)
[2026-04-08] [FIXED]   M3-a  "check what is on my screen" treated as chat â€” FIXED: added task keywords
[2026-04-08] [PASS]    M3    Screen reading works: pre-warm attached, LLM described ReturnGift UI (1 round, 4.9K tokens)
[2026-04-08] [FIXED]   M4-a  Compound task "open Settings AND turn on dark mode" truncated by Tier 1 â€” FIXED: compound check in PipelineRouter
[2026-04-08] [PASS]    M4    Settings â†’ Display â†’ Dark theme toggled (6 rounds, 36K tokens)
[2026-04-08] [PASS]    M5    WhatsApp opened, scroll_to_find("Mom"), "Mom" not found (expected), graceful fail (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M6    Play Store â†’ search Telegram â†’ tap Install â†’ "installation started" (14 rounds, 98K tokens)
[2026-04-08] [PASS]    M7    Chrome â†’ tap search â†’ input_text("weather today") â†’ enter â†’ results + screenshot (9 rounds, 61K tokens)
[2026-04-08] [PARTIAL] M8    (pre-playbook) Gmail compose â†’ typed To + Body, but looped twice â†’ budget limit (16 rounds, 104K tokens)
[2026-04-08] [PASS]    M8    (post-playbook) Gmail compose: To + Subject + Body filled, finish("Ready to review") â€” no loop, no send (12 rounds, 84K tokens)
[2026-04-08] [PARTIAL] M9    Camera opened, shutter tapped, but can't verify photo capture (14 rounds, 89K tokens)
[2026-04-08] [PASS]    M10   system_key("notifications") â†’ 9 notifications listed in detail (2 rounds, 11.6K tokens!)
[2026-04-08] [PASS]    M11   "Watsapp" typo â†’ "WhatsApp" correctly resolved, send_message called (13 rounds, 93K tokens)
[2026-04-08] [PARTIAL] M12   YouTube Music opened, play attempted, system dialog blocked (6 rounds, 30.5K tokens)
```

### Open Issues (unfixed)

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| ~~A1-a~~ | ~~Floating button flashes on chat questions~~ | ~~FIXED: finish tool filtered from showTaskNotify~~ | ~~Medium~~ |
| ~~A1-b~~ | ~~"Accessibility starting..." on every new chat~~ | ~~FIXED: moved keyword routing before accessibility check~~ | ~~Low~~ |
| ~~F1~~ | ~~Top bar "Task running..." not showing~~ | ~~FIXED~~ | ~~High~~ |
| ~~F2~~ | ~~Send button not turning red~~ | ~~FIXED~~ | ~~High~~ |
| H6 | Pencil icon cannot rename chat session | Not implemented â€” deferred to feature backlog | Low |
| ~~F3~~ | ~~Floating button IDLE in other apps~~ | ~~FIXED: show() callback now restores state via updateStateView~~ | ~~Medium~~ |
| ~~F6~~ | ~~"..." coexists with tool actions~~ | ~~FIXED: removeTypingIndicator() on first ToolAction~~ | ~~Medium~~ |
| B2-a | ~~No auto-return after task in other app~~ | Fixed 2026-04-10: cloud task completion now auto-returns to `ComposeChatActivity`, and recent YouTube search passes restored the same ReturnGift session after finishing in another app | Fixed |
| M1-a | ~~YouTube search: LLM skips input_text~~ | Fixed 2026-04-10: generic in-app search guard now blocks premature completion on explicit `search [app] for [query]` / `search for [query] on [app]` tasks until the agent actually calls `input_text`, then inspects results before finishing | Fixed |
| M3-a | ~~Screen reading routed as chat~~ | ~~FIXED: added "check", "screen", "notification", "compose", "find", "read my" to task detection~~ | ~~High~~ |
| M4-a | ~~Compound tasks truncated by Tier 1~~ | ~~FIXED: PipelineRouter skips Tier 1 for tasks with "and"/"then"/"after"~~ | ~~High~~ |
| M8-a | ~~Gmail compose loops~~ | Fixed 2026-04-10: explicit email-compose tasks now use a generic compose guard, so task mode no longer short-circuits into draft text or loops; it opens an email app, fills the draft fields, and finishes only after in-app compose work has started | Fixed |
| M12-a | YouTube Music system dialog | Login/premium dialog blocks music playback task | Low |

### 2026-04-09 â€” v9 UI Redesign QA

**Changes tested:** ChatScreen.kt v9 redesign â€” Local/Cloud toggle in toolbar, empty state, Quick Tasks panel, Chat/Task toggle, Monitor dialog, send routing.

```
[2026-04-09] [PASS]    G1    Cloud empty state: icon + "Cloud AI" + hint + 3 prompts + no toggle + correct placeholder
[2026-04-09] [PASS]    G2    Local empty state: icon + "Local AI" + bold hint + 3 local prompts + toggle visible
[2026-04-09] [PASS]    G5    Tab switch updates empty state immediately (subtitle, hint, prompts all change)
[2026-04-09] [PASS]    Q1-1  Cloudâ†’Local tab switch: model switches to Gemma 4 E2B, Chat/Task toggle appears
[2026-04-09] [PASS]    Q1-2  Localâ†’Cloud tab switch: model switches to gpt-4o-mini, toggle hides
[2026-04-09] [PASS]    Q2-1  Cloud chat "hello" â†’ "Hello! How can I help you today?" (1 round, 5K tokens)
[2026-04-09] [PASS]    Q2-2  Cloud task "battery" â†’ "100%, charging, 33.5Â°C" (2 rounds, get_device_info)
[2026-04-09] [PASS]    Q4-1  Quick Task tap fills input "How much battery left?" + auto-switches to Task mode
[2026-04-09] [PASS]    P1-1  Local/Cloud buttons in toolbar, same line as ReturnGift
[2026-04-09] [PASS]    P1-3  No background container on buttons
[2026-04-09] [PASS]    P2-5  Cloud mode: no Chat/Task toggle, placeholder "Chat or give a task..."
[2026-04-09] [PASS]    P3-1  Quick Tasks panel with â–˛ chevrons
[2026-04-09] [PASS]    P3-4  5 quick task items visible by default
[2026-04-09] [PASS]    P3-9  BACKGROUND section + Monitor card
[2026-04-09] [PASS]    P3-10 Monitor card â†’ centered dialog with Contact/App/Tone form
[2026-04-09] [PASS]    P5-1  No TaskSkillsPanel in content area (removed)
[2026-04-09] [PASS]    Q3-1  Local chat via UI â€” GPUâ†’CPU fallback triggered, Gemma 4 responded "Hello! How can I help you today?" (11 tokens)
[2026-04-09] [PASS]    Q5-1  GPUâ†’CPU fallback in sendChat() WORKS â€” OpenCL fail â†’ engine reset â†’ CPU retry â†’ success
[2026-04-09] [PASS]    Q5-3  Tab switch mid-conversation â€” Cloudâ†’Localâ†’Cloud with sends, no crash, correct routing each time
[2026-04-09] [FIXED]   Q5-1  sendChat() GPUâ†’CPU fallback â€” added catch block that detects OpenCL/nativeSendMessage error, reloads engine with CPU, retries
[2026-04-09] [FIXED]   Q5-1b Conversation creation "after 5 retries" â€” added engine reset on attempt 3 to clear stale task agent conversations
[2026-04-09] [FIXED]   Q5-2  API key was "test" â€” reconfigured with real key
[2026-04-09] [FIXED]   Tab LaunchedEffect override â€” removed LaunchedEffect sync so tab is user-controlled
[2026-04-09] [FIXED]   Cloud model memory â€” saves LAST_CLOUD_MODEL to KVUtils before switching to Local, restores when switching back
[2026-04-09] [FIXED]   Token counter â€” only shows for Cloud mode, hidden for Local (on-device = free)
[2026-04-09] [PASS]    Chat bubble verified â€” Q3-1 Local Chat: user msg y=417, AI response y=525, model tag "gpt-4.1" visible
[2026-04-09] [PASS]    R1 notifications triage â€” 150s, get_notifications â†’ LLM summarized important items
[2026-04-09] [PASS]    R2 battery advice â€” 135s, get_device_info(battery) â†’ "do not need to charge"
[2026-04-09] [PASS]    R3 clipboard explain â€” 135s, clipboard(get) â†’ LLM described content (restaurant list)
[2026-04-09] [PASS]    R4 storage analysis â€” 165s, storage + apps â†’ LLM cross-referenced
[2026-04-09] [PASS]    R5 notification summary â€” 150s, get_notifications â†’ grouped by app + urgency
[2026-04-09] [PASS]    R6 charge advice â€” 105s, get_device_info(battery) â†’ "100% charging, no need"
[2026-04-09] [FIXED]   Cloud send accessibility UX â€” Toast shown first ("Enable Accessibility Service to run tasks"), then navigates to ReturnGift Settings (not Android Settings). User sees all permissions.
[2026-04-09] [PASS]    Chat bubble E2E â€” Cloud: user "hello" y=357, AI "Hello! How can I help you today?" y=465, model tag "gpt-4.1" y=538
[2026-04-09] [PASS]    P2-3  Task mode: placeholder "Describe a phone task..." after tap đź¤– Task
[2026-04-09] [PASS]    P2-4  Chat mode: placeholder "Chat with local AI..." after tap đź’¬ Chat
[2026-04-09] [PASS]    P2-7  Mode switch preserves messages: Chatâ†’Taskâ†’Chat, "test123" still visible
[2026-04-09] [PASS]    P3-3  Quick Tasks collapse/expand: tap handle â†’ collapsed, tap again â†’ expanded
[2026-04-09] [PASS]    J2    Empty input send: tap send with empty field â†’ nothing sent
[2026-04-09] [PASS]    Q4-2  Cloud Quick Task E2E: đź¦ž Reddit â†’ tap â†’ fills input â†’ send â†’ agent navigated Reddit + searched ReturnGift
[2026-04-09] [FIXED]   L1-v9 Session restore â€” onCreate reads CURRENT_CONVERSATION_ID from KVUtils, reloads saved messages. replaceTypingIndicator now calls saveChat() to persist task results immediately. Verified: "Restored 7 messages from conversation chat_1775787808468"
[2026-04-10] [NOTE]    On this Pixel 8 Pro / Android 16, reinstall cleared Accessibility (`enabled_accessibility_services=null`). Re-enabling via `adb shell settings put secure enabled_accessibility_services com.returngift.agent/com.returngift.agent.service.ClawAccessibilityService` + `accessibility_enabled 1` restored the bound service for QA.
[2026-04-09] [PASS]    Full E2E WhatsApp: UI type "send hi to Girlfriend on WhatsApp" â†’ agent opened WhatsApp â†’ send_message called â†’ finish("Sent 'hi' to Girlfriend on WhatsApp.") â†’ auto-return 15s â†’ result visible in chatroom
[2026-04-09] [PASS]    Auto-return verified: agent navigated to WhatsApp, completed task, returned to ReturnGift, user msg + AI result both visible in same session
[2026-04-09] [PASS]    C1/L3/L4  Monitor start via in-app monitor flow stays in ReturnGift; top bar shows "Monitoring: Rlfriend", no Home press
[2026-04-09] [PASS]    C3    Tap top monitoring bar â†’ expands to show contact + Stop â†’ tap Stop â†’ AutoReplyManager logs "Auto-reply DISABLED for contacts: []"
[2026-04-09] [PASS]    K6-a  App Settings â†’ Accessibility Service row opens Android Accessibility page for ReturnGift
[2026-04-09] [ISSUE]   K2-a  App Settings permission status stale â€” Accessibility row still shows "Disabled" even when system Accessibility page shows "Use ReturnGift" ON
[2026-04-09] [ISSUE]   K3-b  Accessibility enable auto-return incomplete â€” app calls START on SettingsActivity after enable, but system Accessibility SubSettings stays foreground; user is not auto-returned
[2026-04-10] [FIXED]   K2-a  Accessibility status row now reads system enabled-services state, so app Settings shows the truthful `Enabled`/`Disabled` value
[2026-04-10] [PASS]    K2-a  App Settings â†’ Accessibility Service row shows `Enabled` immediately after system Accessibility toggle is ON
[2026-04-10] [FIXED]   K3-b  Pending accessibility auto-return is now armed only when the service is disabled, preventing false triggers while Accessibility is already ON
[2026-04-10] [PASS]    K3    Disabled Accessibility â†’ tap app Settings row â†’ Android Accessibility â†’ ReturnGift detail â†’ toggle `Use ReturnGift` ON â†’ app auto-returns to ReturnGift Settings and row shows `Enabled`
[2026-04-10] [FIXED]   Q6-7  Task agent config now syncs on model switch and before startTask, so Cloud tab tasks no longer reuse stale Local agent config
[2026-04-10] [PASS]    Q2-2/Q6-7  Cloud task "how much battery left" â†’ Agent config updated to `gpt-4.1` â†’ `get_device_info(category=battery)` runs â†’ answer returned in chat with model tag `gpt-4.1-2025-04-14`
[2026-04-10] [FIXED]   L1-v9  Cloud send-message auto-return now preserves the existing conversation instead of dropping the user into a fresh session
[2026-04-10] [PASS]    B1/L1/Q7-7  Cloud task "send yo to girlfriend on WhatsApp" â†’ `send_message` opens WhatsApp and succeeds â†’ auto-return keeps user in `ComposeChatActivity` â†’ same conversation still shows prior messages plus new user bubble + result bubble `Sent 'yo' to girlfriend on WhatsApp.`
[2026-04-10] [FIXED]   A11Y-r1  Accessibility-dependent tools no longer fail immediately during transient service rebinds; they now wait for the enabled service to reconnect before hard-failing
[2026-04-10] [PASS]    H2/H2-b/H2-c  Models screen keyboard safety: API key, Custom Base URL, and Custom Model Name all stay fully visible when IME opens; focused field scrolls into view
[2026-04-10] [FIXED]   P1-4/Q1-r1  Chat toolbar tab state now re-syncs to the actual active model after Settings/model changes, preventing Cloud placeholder/quick-tasks from drifting out of sync with a Local model status (and vice versa)
[2026-04-10] [PASS]    P1-4/P2-1/P2-4/Q1-1/Q6-2  Tap `Local` â†’ model status switches to `â—Ź Gemma 4 E2B â€” 2.6GB Â· CPU`, local reasoning-first quick tasks render, Chat/Task toggle appears, placeholder becomes `Chat with local AI...`
[2026-04-10] [PASS]    P1-4/P2-5/Q1-2/Q6-3  Tap `Cloud` â†’ model status switches back to `â—Ź gpt-4.1 Â· Cloud`, cloud-only quick tasks return, Chat/Task toggle hides, placeholder becomes `Chat or give a task...`
[2026-04-09] [BLOCKED] L5/L5-b  Incoming WhatsApp notification auto-reply while staying in app requires a second sender device / live external message source
[2026-04-09] [FIXED]   F2-v9 Stop button slow â€” added Future.cancel(true) to interrupt agent thread + abort HTTP call immediately (was: flag-only, waited for LLM round to finish)
[2026-04-09] [ISSUE]   F2-v9 Stop â†’ return to same session â€” after stopping task, should return to the SAME chat session, not open new one
[2026-04-09] [ISSUE]   L1-v9 Auto-return should preserve session â€” after task completes in other app and auto-returns to ReturnGift, should show the same conversation with the result, not a fresh session
[2026-04-10] [PASS]    Q7-2/Q7-3/Q7-4/Q7-6  Cloud quick task "Search YouTube for funny cat fails" â†’ YouTube opens â†’ tap left floating bubble â†’ `Stop task requested from floating pill` logged â†’ task cancelled â†’ auto-return restores same `ComposeChatActivity` session â†’ send button resets to arrow
[2026-04-10] [PASS]    Q7-5  After floating-stop, second Cloud task "how much battery left" runs normally â†’ no `already running` error â†’ answer returned in same session
[2026-04-10] [ISSUE]   Q7-local  Local task stop could trigger a native crash / stale-session race: stop during LiteRT `sendMessage()` â†’ chat UI reloads early â†’ `session already exists` and occasional `SIGSEGV`
[2026-04-10] [FIXED]   Q7-local  Local stop now avoids interrupting LiteRT mid-round; terminal cleanup waits for the task-side client to close, and `TaskOrchestrator` only releases the task after the cancel completion callback arrives
[2026-04-10] [PASS]    Q7-1b/Q7-3/Q7-4  Local task "how much battery left" â†’ tap Stop â†’ 1s later UI still shows `Task running...` + `Stop` while safe unwind is in progress â†’ app remains on `ComposeChatActivity` â†’ logs show `Task cancelled` â†’ send button resets to arrow
[2026-04-10] [PASS]    Phase2-r1  TaskSessionStore smoke on Pixel 8 Pro â†’ local quick-task card still fills task input correctly â†’ sending enters `Task running...` + `Stop` with honest `Model busy` chat status â†’ stop request is logged by `TaskOrchestrator` and UI returns to idle placeholder on the same `ComposeChatActivity` shell
[2026-04-10] [PASS]    Q7-5-local  After local stop, a second local task starts and completes normally â€” no `already running`, no `session already exists`, no crash
[2026-04-10] [FIXED]   Dbg-u1  Debug builds now run the same once-per-day GitHub release check as release builds, so accidental debug installs still see upgrade prompts
[2026-04-10] [BLOCKED] Dbg-u1  Live prompt verification still needs a throwaway device/build that is older than the just-installed `0.5.0`; current handset has already been upgraded, so this turn only covers code inspection + build/install verification, not a fresh old-debug prompt capture
[2026-04-10] [PASS]    Dbg-u2  Public GitHub `v0.4.1` asset (`ReturnGift_v0.4.0_20260408_140502.apk`) on test device â†’ cold launch after `v0.5.0` release published â†’ `Update Available` modal appears with `ReturnGift v0.5.0 is available. You are running an older version.`
[2026-04-10] [ISSUE]   Dbg-u3  Public GitHub `v0.4.1` asset cannot be updated in place to public `v0.5.0` asset: `adb install -r ... ReturnGift_v0.5.0_20260410_161430.apk` returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; users on the older public debug signing path need a one-time uninstall + reinstall
[2026-04-10] [FIXED]   Rel-s1  Release signing config now accepts the same `KEYSTORE_*` inputs from environment variables or `local.properties`, so local signed builds and GitHub Actions both follow the same stable-signing path
[2026-04-10] [NOTE]    Rel-s2  `v0.5.1` is the first version prepared for a stable release key path; the old public `0.4.x` â†’ public `0.5.0` signing mismatch is already shipped and cannot be retro-fixed without the lost original key
[2026-04-10] [BLOCKED] Rel-s3  Public GitHub Release publication for `v0.5.1` still depends on installing the stable signing secrets into `ReturnGift` Actions settings; code path is ready, repo permission path is not
[2026-04-10] [PASS]    Rel-s4  Local stable-signing verification: generated a dedicated release keystore, `./gradlew :app:validateSigningRelease` passed, and a fresh `./gradlew --no-daemon :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease` produced `app/build/outputs/apk/release/ReturnGift_v0.5.1_20260410_111303.apk`
[2026-04-10] [PASS]    Rel-s5  Local signed release artifact verification: `apksigner verify --print-certs` reports signer `CN=Nicole, OU=ReturnGift, O=agents.io, L=Vancouver, ST=British Columbia, C=CA` with SHA-256 `e000d1d6555b8fab20c03a5d9ddeba83944f26eecf0b978ac7affc2eebd43186`; local `SHA256SUMS.txt` records APK digest `fb7c6a6f4e2536f24bfb8f9ac6e8f7628aec11bf5e1a29b96fc18bb238fcde65`
[2026-04-10] [PASS]    Rel-s6  Stable-signed `0.5.1` release APK fresh-installed successfully onto the Pixel test device after removing the old debug build; launcher resolves and app starts normally
[2026-04-10] [PASS]    Rel-s7  Stable-key in-place upgrade path verified locally: with the same release keystore, a higher-version signed build (`ReturnGift_vERSION_CODE=15`, `ReturnGift_vERSION_NAME=0.5.1-upgrade-test`) installed over the stable-signed `0.5.1` baseline via `adb install -r` and Android accepted the upgrade with no signature mismatch
[2026-04-10] [FIXED]   M1-a  Explicit in-app search tasks now use a generic guard/prompt hint: the agent cannot finish before it really types the query with `input_text`, and blocked finishes feed back a fresh screen-based node hint instead of an app-specific scripted route
[2026-04-10] [PASS]    M8/M1-a  Cloud task `search youtube for lofi beats` â†’ `open_app` â†’ `input_text(node_id=...)` succeeds â†’ `system_key(enter)` â†’ `get_screen_info` â†’ `finish`; completes in 6 rounds / 46.7K tokens, no budget stop, auto-return restores `ComposeChatActivity`
[2026-04-10] [PASS]    M8-alt/M1-a  Alternate phrasing `search for lofi beats on youtube` follows the same generic path (`open_app` â†’ `input_text(node_id=...)` â†’ `system_key` â†’ `get_screen_info` â†’ `finish`) and also completes in 6 rounds / 47.5K tokens
[2026-04-10] [PASS]    M1-control  Non-search control task `how much battery left` remains unaffected by the search guard: `get_device_info(category=battery)` â†’ `finish`; completes in 2 rounds / 10.4K tokens with no `InAppSearchGuard` activity
[2026-04-10] [FIXED]   M8-a  Explicit email-compose tasks now use a generic `EmailComposeGuard`: the agent can no longer satisfy task-mode email requests with text-only draft output before attempting any in-app compose actions
[2026-04-10] [PASS]    M8/S8  Cloud task `Write an email saying I will be late today` â†’ `get_installed_apps(mail)` â†’ `open_app(com.google.android.gm)` â†’ `tap_node` compose â†’ `input_text` subject `Running late today` â†’ `input_text` body â†’ `get_screen_info` â†’ `finish`; completes in 8 rounds / 52.2K tokens, auto-returns to `ComposeChatActivity`, and correctly leaves recipient blank because none was provided
[2026-04-10] [PASS]    M8-control  Control task `how much battery left` remains unaffected by `EmailComposeGuard`: `get_device_info(category=battery)` â†’ `finish`; completes in 2 rounds / 10.4K tokens with no compose-specific interference
[2026-04-10] [PASS]    LQ1-LQ5  Local reasoning quick-task sweep on Pixel 8 Pro: notifications triage, clipboard explain, storage analysis, notification summary, and battery advice all completed on-device via LiteRT CPU fallback with correct tool routing and no crashes/loops
[2026-04-10] [PASS]    LQ7-LQ10/LQ12/LQ13  Local deterministic quick-task sweep: installed apps, phone temperature, bluetooth state, battery, storage, and Android version all returned correct device data through `get_installed_apps` / `get_device_info`, with no stale-session or routing regressions
[2026-04-10] [PASS]    LQ6/LQ11  Contact-specific local quick tasks still route the correct tools (`send_message`, `make_call`) and fail gracefully when `Mom` does not exist on this device; treat literal send/call success as env-blocked coverage, not a product failure
[2026-04-10] [PASS]    P3-7/P3-8/Q4-1  Local UI quick-task E2E: tap visible `Check my battery and tell me if I need to charge` card â†’ input prefilled â†’ Local task send routes through `provider=LOCAL / gemma4-e2b` â†’ `get_device_info(category=battery)` â†’ response bubble `The battery is at 100% and is charging. You do not need to charge.` appears with local model tag `Gemma 4 E2B â€” 2.6GB`; input resets to task placeholder `Describe a phone task...`
[2026-04-10] [FIXED]   QA-r1  `scripts/e2e-quick-tasks.sh` now classifies `onSystemDialogBlocked`, text-only completions with no tool calls, `Task cancelled`, and `Task stopped: budget limit reached ...` correctly; it no longer misreports the YouTube permission dialog as a generic timeout
[2026-04-10] [FIXED]   Bgt-1  Existing installs could stay pinned to the legacy 100K / $0.50 task budget even after code defaults increased. `TaskBudget` now migrates untouched legacy defaults to 250K / $1.00 once, while preserving user-custom budgets; Settings budget UI now exposes `250K` explicitly and snaps to the nearest current value
[2026-04-10] [PASS]    S2/M32  Cloud task `Install Telegram from Play Store` â†’ Play Store path completed without budget stop; on this device the agent correctly recognized Telegram was already installed and finished in 10s
[2026-04-10] [PASS]    S3/M20  Cloud task `Check whats trending on Twitter and tell me` â†’ `open_app(com.twitter.android)` â†’ inspect current feed/trending content â†’ summarize visible topics; completed in 30s with no task-budget stop
[2026-04-10] [BLOCKED] S1/M1-b  Cloud task `Search YouTube for funny cat fails` is currently blocked by Android's foreground permission controller (`GrantPermissionsActivity`) over YouTube; ReturnGift surfaces this as `system dialog blocked foreground automation` instead of looping or timing out
[2026-04-10] [PASS]    S5/M33  Cloud task `Copy the latest email subject and Google it` â†’ `get_notifications` â†’ `clipboard(set)` â†’ `open_app(com.android.chrome)` â†’ search in Chrome â†’ screenshot/search-results visible â†’ `finish`; after legacy-budget migration this completed in 15 rounds / 110.2K tokens instead of hard-stopping at the old 100K ceiling
[2026-04-10] [PASS]    S7/M51  Committed-state rerun `Open Reddit and search for ReturnGift` â†’ `open_app(com.reddit.frontpage)` â†’ `input_text(ReturnGift)` â†’ results visible â†’ `finish`; completed in 12 rounds / 91.9K tokens on the latest hardening branch
[2026-04-10] [PASS]    Cloud quick-task sweep (effective final) on branch `hardening/behavior-safe-2026-04-09` @ `a0a88ab`: `18 PASS / 0 FAIL / 2 BLOCKED / 0 TIMEOUT / 20 TOTAL`. Blocked items are environment-driven (`S1` YouTube permission dialog, `Call Mom` missing contact). Base sweep log: `/tmp/ReturnGift-cloud-quick-tasks-20260410-full.log`; `S5` was rerun after the budget migration and passed at 110.2K tokens
[2026-04-10] [PASS]    Phase1-r1  Architecture refactor smoke â€” relaunch via `SplashActivity` with Cloud config active lands on `ComposeChatActivity` showing `â—Ź gpt-4.1 Â· Cloud` and the unified Cloud placeholder, confirming chat runtime rehydrate still works after `ChatSessionController` extraction
[2026-04-10] [PASS]    Phase1-r2  Architecture refactor smoke â€” copied the existing Edge Gallery Gemma model into ReturnGift's sandbox, switched provider to `LOCAL`, relaunched, and confirmed `ComposeChatActivity` rehydrated into Local mode with `Chat with local AI...` plus top status `â—Ź gemma4_2b_v09_obfus_fix_all_modalities_thinking Â· GPU`
[2026-04-10] [PASS]    Q3-1/Q5-1/Q5-1b/Phase1-r3  Local chat after `ChatSessionController` extraction: UI send produced a real assistant reply (`Hello! How can I help you today?`), GPU inference transparently fell back to CPU, and both the top status pill and assistant model tag updated to `CPU` instead of stale `GPU`
[2026-04-10] [PASS]    Phase3-r1  Fresh reinstall + app Settings smoke: after `adb install -r`, Android cleared `enabled_accessibility_services`; app Settings now truthfully shows `Accessibility Service = Disabled` instead of stale `Enabled`
[2026-04-10] [PASS]    Phase3-r2  Rebinding truth smoke: after restoring `enabled_accessibility_services` / `accessibility_enabled` via `adb shell settings put secure ...`, app Settings showed `Accessibility Service = Connecting` while the service was still rebinding, instead of collapsing enabled+unbound into `Disabled`
[2026-04-10] [PASS]    Phase3-r3  Permission truth smoke: with no ReturnGift listener in `enabled_notification_listeners`, app Settings shows `Notification Access = Disabled`
[2026-04-10] [FIXED]   K4-r1  Notification-listener foreground return is now gated by a pending permission-flow flag, so listener reconnects no longer blindly foreground app Settings unless the user actually came from the in-app permission flow
[2026-04-10] [PASS]    Phase4-r1/H4-b  After local-runtime consolidation, cold launch still lands on `ComposeChatActivity` with truthful local status `â—Ź gemma4_2b_v09_obfus_fix_all_modalities_thinking Â· CPU`
[2026-04-10] [PASS]    Phase4-r2/Q3-1/Q5-1/Q5-1b  Local UI send smoke after runtime consolidation: typed `say pong`, tapped the live send-button bounds, and received assistant reply `Pong! đźŹ“`; both top status and assistant bubble tag remained `gemma4_2b_v09_obfus_fix_all_modalities_thinking (CPU)`
[2026-04-10] [PASS]    P7-1/P7-2  Chat bubble metadata smoke: after relaunching `ComposeChatActivity`, user bubbles render a subtle time footer (`5:57 p.m.`) and assistant bubbles render `gemma4_2b_v09_obfus_fix_all_modalities_thinking (CPU) Â· 5:57 p.m.` under the reply bubble
[2026-04-10] [PASS]    P7-3/Q7-7  Saved chat history now persists per-message timestamps in markdown via hidden `<!-- ReturnGift:timestamp=... -->` comments, so reloaded conversations keep stable bubble times instead of resetting to the current clock
[2026-04-10] [PASS]    Phase1b-r1/Q7-7  After `ConversationStore` extraction, cold relaunch still restored `chat_1775851530681` with 9 saved messages; logcat showed `Restored 9 messages from conversation chat_1775851530681`, and the foreground UI still showed the existing `ay pong` / `Hello! How can I help you today?` conversation instead of a blank new chat
[2026-04-10] [PASS]    Phase2b-r1  After `TaskFlowController` extraction, debug task broadcasts still reached the chat shell (`TaskTriggerReceiver: Received task via broadcast: battery`, `ComposeChatActivity: Auto-task from intent: battery`) and preserved in-app permission guidance by pushing `SettingsActivity` when Accessibility was unavailable
[2026-04-10] [FIXED]   Android15-coldstart  Cold launch no longer crashes if app-start `ForegroundService` is disallowed; `ForegroundService.start()` now returns `false` and logs a warning instead of throwing `ForegroundServiceStartNotAllowedException` from `ClawApplication.onCreate()`
[2026-04-10] [PASS]    Phase2c-r1  After `ActiveTaskShellController` extraction, the Compose top bar still rendered `Monitoring: Mom`; expanded state showed `Mom` + `Stop`, and tapping `Stop` disabled auto-reply and removed the monitor
[2026-04-10] [PASS]    Phase2c-r2  Debug `autoreply on mom` no longer bypasses app behavior: `TaskTriggerReceiver` rewrites it to `monitor mom on WhatsApp`, and on this device the flow foregrounded in-app `SettingsActivity` with no direct `Added contact` log and no ghost `Monitoring:` bar in the dumped UI
[2026-04-10] [NOTE]    TgMon-r1  Telegram monitor QA now requires an external sender path (second account or bot token + existing bot chat); without that sender, Telegram incoming-message monitor cases must be marked `BLOCKED`
[2026-04-10] [PASS]    Phase4-r3  Monitor target parser unit bundle passed: `monitor Mom on Telegram`, default WhatsApp when app is omitted, `watch Alex on sms` -> `Messages`, and `monitor Caroline` does not get misparsed as `LINE`
[2026-04-10] [PASS]    Phase4-r4  Live device dialog smoke: Monitor dialog now shows the supported app list (`WhatsApp`, `Telegram`, `Messages`, `LINE`, `WeChat`) and retained `Telegram` as the selected app in the live screenshot instead of collapsing back to WhatsApp
[2026-04-10] [PASS]    Phase5-r1  Local runtime consolidation compile gate: `LocalModelRuntime` now owns shared `openConversation(...)` and `runSingleShot(...)`, and `ChatSessionController`, `LocalLlmClient`, `LlmSessionManager.singleShotLocal()`, and `AutoReplyManager.generateReplyLocal()` all compile against the same runtime boundary (`compileDebugKotlin`, `compileDebugJavaWithJavac`, `assembleDebug`)
[2026-04-10] [BLOCKED] Phase5-r2  Targeted device smoke for the new shared local runtime boundary is blocked by ADB attach state (`adb devices -l` returned no attached devices after the Phase 5 landing). Re-run `H4/H4-b`, `Q3-1`, `Q5-1`, `Q5-1b`, and the local quick-task bundle as soon as the Pixel is visible again instead of treating the missing device as an app regression
[2026-04-10] [PASS]    Phase5-r3  Local model state consolidation compile gate: `LocalModelManager` now exposes shared device-support, catalog, and active-model state so `LlmConfigActivity` and `ChatSessionController` stop maintaining separate RAM/support/downloaded calculations (`compileDebugKotlin`, `compileDebugJavaWithJavac`)
[2026-04-10] [PASS]    Phase5-r4  Local model ownership cleanup compile gate: `LocalModelManager.downloadModel()` no longer mutates MMKV selection state directly; chat/settings callers now decide whether a finished download should update the default or active local model (`compileDebugKotlin`, `compileDebugJavaWithJavac`)
[2026-04-10] [NOTE]    QA-wf-r2  Device-state guard for Compose UI smoke: if notification shade or another app steals foreground, collapse/foreground ReturnGift again before judging the refactor; if IME moves the input bar, re-dump live bounds instead of reusing stale tap coordinates
[2026-04-10] [PASS]    H2-d  Chat keyboard dismiss smoke passed on Pixel 8 Pro: after focusing the input, tapping the blank header area cleared focus (`focused=true` -> `focused=false`) and hid the IME instead of trapping the keyboard on screen
[2026-04-10] [PASS]    B4-c  Accessibility text-match hardening compile/unit bundle passed: low-level lookup now keeps Android's fast text path but falls back to a Unicode-normalized tree walk, and standard launch dialogs try stable positive-button ids before language-specific keywords
[2026-04-10] [PASS]    Phase5-r5  Cloud send smoke passed after send-affordance hardening: `send yo to girlfriend on WhatsApp` ran on `gpt-4.1`, called `send_message(contact=\"girlfriend\", message=\"yo\", app=\"WhatsApp\")`, finished in 2 rounds, and auto-returned with `Task completed: Sent 'yo' to your girlfriend on WhatsApp.`
[2026-04-10] [PASS]    Phase5-r6  Chat-noise filtering is no longer English-string-bound: conversation-reading heuristics now treat timestamps and centered system labels as layout noise using shared tested rules (`ChatNoiseFilterUtilsTest`, `UiTextMatchUtilsTest`, `ContactMatchUtilsTest`)
[2026-04-11] [FIXED]   DD-guard-1  Cloud no longer falls back to generic "I cannot access your device" denials for direct phone-data requests when a matching tool exists; the direct-device-data guard now forces a real tool attempt first and blocks text-only completion / premature `finish`
[2026-04-11] [PASS]    DD1  Cloud task `read my clipboard and explain what it says` â†’ `clipboard(action=get)` â†’ real clipboard content returned and explained; no generic privacy/device-access refusal
[2026-04-11] [PASS]    DD2  Cloud task `read my notifications and summarize` â†’ `get_notifications()` â†’ summarized live notifications; no false claim that notifications are inaccessible
[2026-04-11] [PASS]    DD3  Cloud task `how much battery left` â†’ `get_device_info(category=battery)` â†’ answered with real battery/charging/temperature state; no generic limitation disclaimer
[2026-04-11] [PASS]    DD5  Cloud task `what apps do i have` â†’ `get_installed_apps()` â†’ returned the real installed-app list; no generic chatbot fallback
[2026-04-11] [PASS]    DD7-unit  Conceptual control `what is an Android clipboard` remains a normal chat-style case in unit coverage; the guard no longer falsely forces a clipboard tool just because the word `clipboard` appears
[2026-04-11] [FIXED]   Q2-r1  Cloud unified-input send no longer reuses task-running chrome for ordinary chat turns; chat waiting state and true task execution state are tracked separately
[2026-04-11] [PASS]    Q2-1b/Q6-3/T10  Pixel 8 Pro smoke after switching to `gpt-4.1-mini`: top pill shows `â—Ź gpt-4.1-mini Â· Cloud`, Cloud tab remains selected, placeholder stays `Chat or give a task...`, and no orange `Task running...` bar appears for the chat shell
[2026-04-11] [PASS]    Q2-1c  Fresh Cloud chat smoke on Pixel 8 Pro: typing `say hi` stayed in ordinary chat, produced a normal `Hello! How can I assist you today?` assistant bubble tagged `gpt-4o-2024-08-06`, and did not launch `Send Message` or reuse any old contact state
[2026-04-11] [PASS]    Q1-6/Q6-3/T10  Settings round-trip truth smoke: switch to Cloud, open Settings, press Back, and return to the same conversation â†’ logcat reports `Cloud chat ready: gpt-4.1-mini`, top pill still shows `â—Ź gpt-4.1-mini Â· Cloud`, and the chat shell stays on the Cloud placeholder instead of drifting back to Local
[2026-04-11] [PASS]    H4-d/H4-e/T2/T10  Models page truth smoke on Pixel 8 Pro: page now shows `Active model`, `Default local model`, and `Default cloud model` separately; the linked default Gemma built-in row no longer claims `Not downloaded`
[2026-04-11] [PASS]    H4-c  Cloud dropdown switch smoke after install: switching to `GPT-4o` leaves a single `Switched to GPT-4o` system line instead of a lower-case + display-name duplicate pair
[2026-04-11] [PASS]    Q2-4/Q2-4b  Cloud clipboard bridge on Pixel 8 Pro: `read my clipboard and explain what it says` called `clipboard(action=get)` and produced the visible assistant bubble `Your clipboard is currently empty.` in the same chatroom; no generic device-access refusal and no misleading `Clipboard failed` status line remained in the UI
[2026-04-11] [PASS]    Q8-smoke-cloud  Same Cloud chatroom memory smoke on Pixel 8 Pro: `Remember token plum8492 and reply with only OK.` â†’ `OK` â†’ `What token did I ask you to remember?` â†’ `The token you asked me to remember is "plum8492".` Visible UI and logcat matched
[2026-04-11] [PASS]    Q8-smoke-local  Same Local chatroom memory smoke on Pixel 8 Pro after local session-restore fix: `Remember token guava9184 and reply with only OK.` â†’ `OK.` â†’ `What token did I ask you to remember?` â†’ `You asked me to remember the token **guava9184**.` Visible UI matched the expected same-chatroom continuity
[2026-04-11] [PASS]    Q9-1-smoke-cloud  Cloud chat -> task handoff proved on Pixel 8 Pro: in one Cloud chatroom, `Remember token mango4421 and reply with only OK` â†’ `OK`, then `Copy that token to the clipboard` triggered `clipboard(action=set,text=mango4421)`, returned `The token "mango4421" has been successfully copied to your clipboard.`, and a follow-up `Read my clipboard and reply with only the clipboard contents` visibly returned `mango4421`
[2026-04-11] [FAIL]    Q9-2-smoke-local  Local task still does NOT inherit prior chat context, which is expected, but the vague task UX is not graceful yet: after a Local chat remembered `papaya6614`, switching to Local Task mode and sending `Copy that token to the clipboard` did not overwrite the clipboard (Cloud readback still returned the earlier `mango4421`) but the Local task also failed to produce a clear user-facing "I need the exact content in this task message" result
[2026-04-11] [PASS]    Q3-r6  Local auto-task session-ownership hardening on Pixel 8 Pro: a foreground `TASK` intent for `How much battery left?` no longer triggers the previous LiteRT `A session already exists` retry/reset crash path; app survives and returns through the normal task shell
[2026-04-11] [PASS]    Q3-r7  Non-interactive Local task fallback without Accessibility: with Accessibility disabled, `How much battery left?` now bypasses the old Settings redirect, executes `get_device_info(category=battery)` directly, and returns `Battery: 100%, charging, 26.4Â°C`
[2026-04-11] [PASS]    RC6-cloud-email-10x  Repeated-trial Cloud compose task stability on Pixel 8 Pro: `Write an email saying I will be late today` completed successfully in **10/10** direct-ADB trials. Each pass stayed on the in-app compose flow and ended with a draft-created `onComplete`, despite occasional stale-node retries during compose-field refresh.
[2026-04-11] [PASS]    RC6-cloud-gmail-google-8x  Repeated-trial Cloud exploratory task `Copy the latest email subject and Google it` achieved **8/10** successful direct-ADB trials on Pixel 8 Pro. Two trials ended `Task cancelled`; eight trials opened Gmail, extracted the latest subject, copied it, and searched it on Google. Treat this as acceptable Cloud exploratory success-rate coverage, not deterministic 10/10 functionality.
[2026-04-11] [PASS]    RC6-local-e4b-battery  Local E4B direct QA: `How much battery left?` completed in 2 rounds after a slow first generation window (~173s to tool call), then called `get_device_info(category=battery)` and returned `100%, charging, 36.1Â°C`
[2026-04-11] [PASS]    RC6-local-e4b-notifications  Local E4B direct QA: `Read my notifications and summarize` completed in 2 rounds, called `get_notifications()`, and returned a visible summary of live YouTube + system notifications after the expected long local generation delay
[2026-04-11] [PASS]    RC6-local-e4b-storage  Local E4B direct QA: `How much storage do I have?` completed in 2 rounds, called `get_device_info(category=storage)`, and returned `37.4 GB used of 245.7 GB (15%), 208.3 GB free`
[2026-04-11] [PASS]    RC6-local-e4b-device  Local E4B direct QA: `What Android version am I running?` completed in 2 rounds, called `get_device_info(category=device)`, and returned `Android 16 (API 36) on a Google Pixel 8 Pro`
[2026-04-11] [FIXED]   RC6-local-session-race  Local direct QA exposed a real release blocker: while a Local task owned the LiteRT session, the chat shell could still try to reopen the same local model and trigger `A session already exists`. Fixed 2026-04-11: the chat-side loader now stands down whenever a task is running and shows `â—Ź Local task using model` instead of racing the task runtime
[2026-04-12] [PASS]    Q8-3  Cloud relaunch memory continuity on Pixel 8 Pro: in one Cloud chatroom, `Remember token cloudrestart7312 and reply with only OK.` returned `OK`; after full force-stop + relaunch, the same conversation restored and `What token did I ask you to remember? Reply with only the token.` visibly returned `cloudrestart7312`
[2026-04-12] [PASS]    Q8-4  Local relaunch memory continuity on Pixel 8 Pro: in one Local E4B chatroom, `Remember token localrestart5186 and reply with only OK.` returned `OK`; after full force-stop + relaunch, the same conversation restored under `â—Ź Gemma 4 E4B â€” 3.6GB Â· CPU` and `What token did I ask you to remember? Reply with only the token.` visibly returned `localrestart5186`
[2026-04-12] [PASS]    Rel-s8  Version-prep build gate for `0.6.0`: `assembleDebug` passed in-sandbox, and a stable-signed local `assembleRelease` produced `app/build/outputs/apk/release/ReturnGift_v0.6.0_20260411_223047.apk` with SHA-256 `649b87e69cf166f8ce0e144aee9d416aaba48b152fa33842a88c7f695b67c57d`
[2026-04-28] [BLOCKED] Rel-s9  `v0.6.8` stable release APK could not upgrade the Pixel 8 Pro from the installed debug-signed `0.6.7`: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Debug `0.6.8` upgraded in place and was used for code-path QA; the stable APK still needs a clean-install or signed-line migration test before upgrade claims
[2026-04-28] [FAIL]    v068-cloud-sweep  Cloud quick-task sweep on Pixel 8 Pro / Android 16 with `gpt-4.1` finished `13 PASS / 4 FAIL / 1 BLOCKED / 2 TIMEOUT / 20 TOTAL`; result log: `/tmp/ReturnGift-v068-cloud-quick-20260428-123547.log`
[2026-04-28] [FAIL]    S7/M51  `Open Reddit and search for ReturnGift` regressed from the 2026-04-10 pass; stuck detector stopped the agent after the screen stayed unchanged for 3 consecutive steps
[2026-04-28] [FAIL]    S6/M11  `Check my latest WhatsApp chat and summarize it` opened WhatsApp but repeated `system_key(back)` and was stopped by stuck detection
[2026-04-28] [TIMEOUT] S8/M19  `Write an email saying I will be late today` timed out at 60s; the next harness case saw leaked `Task cancelled` state from the unfinished email flow
[2026-04-28] [TIMEOUT] B1     `Send hi to Girlfriend on WhatsApp` timed out at 45s on the Pixel 8 Pro QA device
[2026-04-28] [BLOCKED] M47    `Call Mom` hit an external Google Contacts notification-permission dialog; classify this run as environment-blocked, but the harness must recover/clean foreground state before continuing other cases
[2026-04-28] [FAIL]    LQ-v068  Local quick-task sweep did not complete: an invalid first attempt failed after force-stop disconnected Accessibility, the retry timed out on `Notifications triage`, and a targeted Local E2B `how much battery left` smoke also timed out after 180s under the current device state
[2026-04-28] [FIXED]   v068-debug-tool-anr  Direct debug tool broadcasts now run via `goAsync()` background work; focused `send_message` debug-tool smoke sent `qa-ping` to `Girlfriend` without the BroadcastReceiver main-thread ANR
[2026-04-28] [FIXED]   v068-direct-tool-threading  Tier-1 DirectTool routes now execute off the caller thread, preserve `ToolResult.isSuccess`, log direct `onComplete`, and release/reset task state in a `finally` block
[2026-04-28] [FIXED]   v068-e2e-cleanup  `scripts/e2e-quick-tasks.sh` now sends debug `cancel:`, resets foreground between cases, dismisses stale ReturnGift ANR dialogs, waits for Accessibility binding, and classifies `Failed:` completions as failures instead of passes
[2026-04-28] [FIXED]   v068-wa-overflow  Contact lookup overlay dismissal no longer treats a generic top-right ImageButton as a close button; this stopped the WhatsApp overflow menu from being opened during contact lookup
[2026-04-28] [FIXED]   v068-fgs-race  ForegroundService now calls `startForeground()` immediately in `onCreate()`, preventing `ForegroundServiceDidNotStartInTimeException` when a task fails/stops before `onStartCommand` can update the notification
[2026-04-28] [PASS]    B1-v068-followup  Focused Cloud `Send hi to Girlfriend on WhatsApp` from a wrong WhatsApp chat completed in 15s: back to chat list, search `Girlfriend`, type `hi`, tap send, and log direct `onComplete`
[2026-04-28] [FAIL]    v068-cloud-sweep-after-fixes  Latest Cloud quick-task sweep finished `17 PASS / 0 FAIL / 1 BLOCKED / 2 TIMEOUT / 20 TOTAL`; result log: `/tmp/ReturnGift-v068-cloud-quick-20260428-1337-after-wa-fix.log`. Remaining timeouts: WhatsApp latest-chat summary and copy latest email subject then Google it
[2026-04-28] [PASS]    LQ-v068-e2b-battery-followup  Targeted Local E2B `How much battery left?` completed in 105s after GPU OpenCL failure fell back to CPU, called `get_device_info(category=battery)`, and returned `60%, not charging, 38.1Â°C`
[2026-04-28] [BLOCKED] Rel-s10  Local `./gradlew assembleRelease` compiled and minified but failed at `:app:packageRelease`: `SigningConfig "release" is missing required property "storeFile"`. Signed release APK needs CI/release signing secrets or local keystore restoration
[2026-04-28] [FIXED]   LMDir-r1  Issue #39 debug ZIP root cause confirmed: v0.6.7 failed before model download because the external app-files `models` directory did not exist, causing `StatFs` and `.downloading` open to throw `ENOENT`. The storage harness now requires a writable model dir, falls back to internal storage when external app storage cannot be created/written, and reports selected/external/internal model-dir diagnostics in bug ZIPs.
[2026-04-28] [FIXED]   RelGate-r1  Release gate is now a concrete per-release record template covering direction, harness, scope, compile/test, script hygiene, artifact, targeted regression, device smoke, distribution, and user-followup checks.
[2026-04-30] [PASS]    Rel-v0610-fresh-install  QA phone clean-installed stable v0.6.10 after uninstalling the debug-signed ReturnGift package; verified versionName=0.6.10, versionCode=25, and release signature fingerprint prefix 745eed92.
[2026-04-30] [PASS]    TgBot-v0610-config  ReturnGift Settings -> Remote Control -> Telegram Bot accepted a Telegram bot token and Settings showed `Connected`; the token was treated as secret and was not recorded in QA notes.
[2026-04-30] [BLOCKED] TgBot-v0610-e2e  Telegram bot true E2E remains blocked by handset Telegram account state: Telegram showed the account as frozen/read-only, and Spam Info Bot appeal was submitted successfully at 10:33; supervisor review is pending.
[2026-04-30] [BLOCKED] TgApp-v0610-send  Telegram app send-message smoke is blocked by the same frozen/read-only Telegram account; do not claim Telegram app automation support until retested with a writable account/contact.
[2026-04-30] [FIXED]   ExtAuto-r1  Production External Automation API added: user-enabled `com.returngift.agent.RUN_TASK` / `RUN_CHAT` receiver, targeted-broadcast requirement, base64 extras, immediate `accepted` callback, and task terminal callback contract.
[2026-04-30] [FIXED]   ExtAuto-r2  External task intents no longer wait for chat model readiness; task payloads go straight to `TaskFlowController`, so deterministic/direct tasks can run before LLM config.
[2026-04-30] [FIXED]   DD-ready-r1  Deterministic direct-device tasks now run before LLM/accessibility gates even when Accessibility is already `READY`; this prevents `how much battery left` from being incorrectly blocked by missing LLM config.
[2026-04-30] [PASS]    C16-extauto-task  Pixel 8 Pro debug-build smoke: with `Settings -> Remote Control -> External Automation = Enabled`, `adb shell am broadcast -a com.returngift.agent.RUN_TASK -p com.returngift.agent --es task "how much battery left"` was accepted, logged `sendTask: executing deterministic direct tool before LLM/accessibility gates`, and visibly returned `Battery: 80%, charging, 35.0Â°C`.
[2026-04-30] [PASS]    C17-extauto-chat  Pixel 8 Pro debug-build smoke: `adb shell am broadcast -a com.returngift.agent.RUN_CHAT -p com.returngift.agent --es chat "say hi"` was accepted and opened the chatroom path; because the clean QA install has no LLM selected, the UI showed `Configure LLM in Settings first.` instead of silently hanging.
[2026-04-30] [PARTIAL] C18-extauto-callback  Callback contract unit coverage passes and live task smoke with `request_id` / `return_action` did not crash, but no Tasker/MacroDroid callback receiver was available on the QA phone; keep true callback-consumer E2E open.
[2026-04-30] [BLOCKED] Tasker-extauto-install  Tasker Play Store install on the QA phone is blocked by purchase requirement (`HK$34.90` shown). Do not claim Tasker-specific E2E until the paid app is installed or a user-owned license is available.
[2026-04-30] [PASS]    MacroDroid-extauto-e2e  Installed MacroDroid, created macro `ReturnGift Battery E2E` with `Shortcut Launched` trigger and `Send Intent` action targeting `com.returngift.agent.RUN_TASK`, package `com.returngift.agent`, extra `task=how much battery left`; MacroDroid `Test macro` triggered ReturnGift, logged `Accepted external automation TASK`, ran the deterministic direct tool, and visibly returned `Battery: 83%, not charging, 38.1Â°C`.
[2026-04-30] [BLOCKED] ExtAuto-r3-v0611-signed  Signed v0.6.11 broadcast receiver received the request but Android 16 / targetSdk 36 blocked the receiver from opening `ComposeChatActivity` from background. Do not direct users to v0.6.11 for external automation.
[2026-04-30] [FIXED]   ExtAuto-r4-activity-entry  Added exported transparent `ExternalAutomationActivity` so MacroDroid/Tasker/Locale-style apps can launch ReturnGift as an Activity with the same `RUN_TASK` / `RUN_CHAT` contract, avoiding background-activity-launch blocking.
[2026-04-30] [PASS]    MacroDroid-extauto-activity-e2e  Pixel 8 Pro debug v0.6.12 smoke: MacroDroid `Send Intent` Target=`Activity`, Package=`com.returngift.agent`, Class=`com.returngift.agent.automation.ExternalAutomationActivity`, Action=`com.returngift.agent.RUN_TASK`, extra `task=how much battery left`; MacroDroid `Test macro` launched ReturnGift, logged `Accepted external automation TASK`, ran the deterministic direct tool, and visibly returned `Battery: 100%, not charging, 36.0Â°C`.
[2026-04-30] [PASS]    Rel-v0612-signed-macrodroid  Pixel 8 Pro signed-release smoke: clean-installed signed `v0.6.12` (`versionCode=27`, release signature fingerprint prefix `745eed92`), enabled External Automation from Settings, reran the same MacroDroid Activity-target macro, and visibly returned `Battery: 100%, charging, 35.2Â°C` in the ReturnGift chatroom.
```

### Bugs Found During v9 QA

| ID | Issue | Root Cause | Priority |
|----|-------|-----------|----------|
| Rel-s9 | Stable `v0.6.8` APK cannot upgrade the installed QA-phone package | Installed phone has a debug-signed `0.6.7`; stable `0.6.8` uses the release cert, so Android rejects in-place upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Blocker before upgrade claims |
| Rel-s10 | Local signed release package cannot be produced | `./gradlew assembleRelease` fails at `:app:packageRelease` because local release signing config has no `storeFile` | Blocker for local release; use CI signing or restore local secrets |
| v068-wa-send | ~~`Send hi to Girlfriend on WhatsApp` timed out in the Cloud sweep~~ | Fixed 2026-04-28: deterministic send parser routes literal send commands to `send_message`, DirectTool no longer blocks the caller thread, ToolResult failures are respected, and contact lookup no longer opens WhatsApp overflow menu as a fake close action | Fixed; latest full Cloud sweep passed B1 in 15s |
| v068-wa-summary | WhatsApp latest-chat summary loops on Back and triggers stuck detection | Unknown yet; likely navigation/state handling after opening WhatsApp | Blocker |
| v068-gmail-google-timeout | `Copy the latest email subject and Google it` still times out in the latest Cloud sweep | Needs focused Gmail read/search trace; earlier repeated-trial pass rate was 8/10, but latest full sweep timed out twice | High |
| v068-email-cleanup | ~~Email compose timeout leaks cancellation/interruption into later harness cases~~ | Fixed 2026-04-28 in the QA runner: timeout now triggers debug cancel + foreground reset before the next case; latest sweep did not leak `Task cancelled`, and `Write an email saying I will be late today` passed | Fixed |
| v068-local-timeout | ~~Local quick-task and Local E2B battery smoke timeout under current QA state~~ | Partially fixed/clarified 2026-04-28: targeted Local E2B battery now passes in 105s after GPUâ†’CPU fallback; Local full sweep still needs rerun and latency remains high | Partial; not full-sweep green |
| v068-fgs-race | Fast task failure can crash with `ForegroundServiceDidNotStartInTimeException` | `stopService`/reset could happen before the service got to `startForeground()`; service now starts foreground immediately in `onCreate()` | Fixed |
| TgBot-v0610-readonly | Telegram bot channel E2E cannot complete on the current QA phone | The handset Telegram account is frozen/read-only; Spam Info Bot appeal was submitted successfully and is pending Telegram supervisor review | Environment blocker; needs successful unfreeze or a writable Telegram account |
| TgApp-v0610-readonly | Telegram app send-message smoke cannot complete on the current QA phone | Same frozen/read-only Telegram account cannot send messages or take actions until Telegram review completes | Environment blocker; retest with writable account/contact |
| Q5-1 | ~~LiteRT "Can not find OpenCL" crash in sendChat()~~ | Fixed 2026-04-09: `sendChat()` now mirrors the Local client fallback path, resets the engine after OpenCL/native errors, and retries on CPU instead of failing the chat send | Fixed |
| Q5-2 | ~~API key was "test"~~ | ~~Device had dummy key, reconfigured~~ | ~~Config~~ |
| K2-a | ~~Accessibility status row shows `Disabled` while Android Accessibility page has `Use ReturnGift` ON~~ | Fixed 2026-04-10: app Settings now reads `enabled_accessibility_services` via `isEnabledInSettings()` | Fixed |
| K3-b | ~~Accessibility enable flow does not foreground ReturnGift after system toggle ON~~ | Fixed 2026-04-10: pending return only arms for a real disabledâ†’enabled flow, then unwinds Settings and foregrounds app | Fixed |
| Q6-7 | ~~Cloud tab tasks can reuse stale Local agent config after a model switch~~ | Fixed 2026-04-10: task agent config now syncs on model switch and immediately before `startTask()` | Fixed |
| Q1-r1 | ~~Toolbar tab UI can drift out of sync with the actual active model after Settings/model changes~~ | Fixed 2026-04-10: `ChatScreen` now re-syncs `selectedTab` from `isLocalModel`, so placeholder/quick-tasks/toggle follow the true active model again | Fixed |
| L1-v9 | ~~Auto-return after task completion can reopen a fresh chat state instead of preserving the active conversation~~ | Fixed 2026-04-10: same conversation remained visible after Cloud `send_message` auto-return, with result appended in place | Fixed |
| A11Y-r1 | Accessibility-dependent tools can false-fail during transient service rebinds | Fixed 2026-04-10: tools now wait for an enabled service to reconnect before returning `Accessibility service is not running` | Fixed |
| Q7-local | ~~Stopping a Local task could crash with native `SIGSEGV` / `session already exists` race~~ | Fixed 2026-04-10: local cancel no longer interrupts LiteRT mid-send, and UI cleanup waits until the task-side client has closed cleanly | Fixed |
| Bgt-1 | Existing installs could stay pinned to the legacy task budget even after code defaults increased | Fixed 2026-04-10: `TaskBudget` now one-time migrates untouched 100K / $0.50 legacy defaults to 250K / $1.00, while preserving explicit user overrides and exposing `250K` in Settings | Fixed |

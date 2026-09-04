# ReturnGift — "Why Does the Release Build Fail?" — Final Diagnostic & Fix Documentation

**Repo:** `RevanthBoina/ReturnGift` — HEAD `80dedbe` (tag **v3.0.12**, Sep 4 2026)
**Scope:** everything below is verified against the live repo (GitHub API, workflow files, `app/build.gradle.kts`, `scripts/ci-preflight.sh`, `AGENTS.md`, `QA_CHECKLIST.md`, `RELEASING.md`) and against the **last successful release** (v2.6.0). No guesses.

---

## 1. TL;DR — the honest answer to "why does it still fail?"

**Because every v3.0.x attempt so far has been a release-variant *compilation* error that exists only in the RELEASE build path — and the project currently has no cheap way to see them before spending a 6-minute CI run per guess.**

Evidence (from the project's own documentation, `AGENTS.md` → *Known CI compile pitfalls*, and `QA_CHECKLIST.md` → RC section, both dated 2026-09-04):

| Release tag | What the project recorded the run failed at | Reverted by |
|---|---|---|
| v3.0.9 | `:app:compileReleaseJavaWithJavac` — Java interop: `WebFetchTool.java` called `new ProvenationTag(kind, origin)` (missing 3rd arg), `ProvenanceHelper.addToFrontmatter(...)` (must be `ProvenanceHelper.INSTANCE.…`), used `ClawApplication` without import; `AskUserTool.java` called 5-arg `ClarificationManager.request(...)` with 4 args | `a7c5f0d` |
| v3.0.10 | `:app:compileReleaseKotlin` — `ComposeChatActivity.kt` imported nonexistent `androidx.compose.runtime.OptIn` | `8dffd69` |
| v3.0.11 / v3.0.12 | `InputTextTool.verifyEnteredText` — unreachable `InterruptedException` catch after the sleep→settle-controller migration | `65efb7a` |
| v3.0.1 – v3.0.8 | Commit messages are all "fix: resolve compilation errors" — the same pattern, one error per run | `7e14966` … `ec3fc7a` |

And their own QA note says it directly: *"Local Gradle compilation is not available in this sandbox; release verification requires the next tagged GitHub Actions run."* → **The workflow is: guess → push tag → wait ~6 min → read the one error → retag.** That loop has now run **25 times** (runs #55–#79) with zero local compilation anywhere.

**So the "why" has three layers, and you only ever see the next one after the previous passes:**

```
Layer 1 (CURRENT)  Release-variant Kotlin/Java compile      → "e: file:///… error: "   / "> Task :app:compileReleaseKotlin FAILED"
Layer 2             Signing / keystore                      → "Keystore was tampered, or password was incorrect" / "Failed to read key"
Layer 3             NDK strip / lintVital / packaging       → "Failed to find NDK" / "No version of NDK matched" / "Lint found fatal errors"
```

No v3.x run has ever been documented getting past Layer 1, so Layers 2–3 are still **unproven** at HEAD — not disproven.

### The one structural mistake that keeps this loop running
1. `testDebugUnitTest` (the old "Quality Gate") compiles only the **debug** variant — the release-variant Java/Kotlin interop errors were **invisible to it**, which is why Quality Gate stayed green while `assembleRelease` died.
2. The Quality Gate was **removed entirely** in `913ccf4` ("make release builds faster and single-APK") to save CI minutes — so now the *only* thing that compiles release code is the 6-minute full `assembleRelease`.
3. `build.yml` and `auto_build_and_test.yml` are both `workflow_dispatch`-only (`on: push` disabled since `6c0917b`) — so even the debug compile gate no longer runs on its own.
4. `scripts/ci-preflight.sh` runs first and is *excellent* (12 guards: BOM-ish patterns, `ic_menu_compose`, `return` in default args, `kotlin.Result` from Java, static-object calls from Java, `compose.runtime.OptIn`, missing imports, brace/structure lexer, orphaned-guard audit, skill-asset drift) — it ran green on HEAD here (`OK kotlin-structure (324 files lexed; braces balanced)`) — **but it's a regex/lexer check, not a compiler. It cannot see `new ProvenanceTag(kind, origin)` style errors.**

---

## 2. Verified state of the project right now (HEAD, v3.0.12)

### Workflow: `.github/workflows/release.yml` (single job, fast path)
```
steps: checkout → Run CI preflight → JDK 17 (temurin) → Setup Android SDK (@v3)
     → Cache Gradle (key: gradle-v3-<hash>) → Derive APK version from tag
     → Prepare release signing → Build signed release APK → (verify w/ apksigner)
     → Upload build log on failure → checksums+rename → release notes → Create GitHub Release
```
Key facts verified in the file:
- Triggers: `push: tags: ['v*']` only (the `3.0.0` / `[0-9]+.[0-9]+.[0-9]+*` patterns are gone — good).
- Version from tag: `code = major*10000 + minor*100 + patch` (v3.0.12 → 30012), name `3.0.12`.
- "Prepare release signing": if all 4 secrets (`ANDROID_KEYSTORE_B64`, `_PASSWORD`, `_ALIAS`, `_KEY_PASSWORD`) are present → decode to `$RUNNER_TEMP/returngift-release.keystore`, `KEYSTORE_TYPE=production`; else → `keytool -genkeypair` CI fallback (`returngift`/`returngift`, `KEYSTORE_TYPE=ci-fallback`). **Writes `local.properties` (root AND `app/`), exports the same to `GITHUB_ENV`.**
- Build step (this is the failing one, step #9): `set -eo pipefail; echo "Building with keystore type: $KEYSTORE_TYPE"; ./gradlew assembleRelease --stacktrace 2>&1 | tee assemble-release.log || { echo "## Build signed release APK Failed"; tail -n 200 assemble-release.log >> $GITHUB_STEP_SUMMARY; exit 1; }`, then `apksigner verify --print-certs` on the found APK.
- Failure artifact: `assemble-release-log` (the full log) — always uploaded on failure, 14-day retention.
- Output: **one** APK `release-artifacts/ReturnGift-release.apk` + `SHA256SUMS.txt` (`ReturnGift.apk` copy removed in `913ccf4`).

### Build config: `app/build.gradle.kts` (verified)
| Setting | Value |
|---|---|
| `namespace` / `applicationId` | `com.returngift.agent` |
| `compileSdk` | `release(37) { minorApiLevel = 1 }` (= platform 37.1) |
| `minSdk` / `targetSdk` | 28 / 36 |
| `ndkVersion` | **`"27.0.12077973"`** (pinned since `ccacf4c`) |
| release signing | `signingConfigs.create("release")`; values read **env → root `local.properties` → `app/local.properties`**; absolute-path aware; **fail-fast gate**: `gradle.taskGraph.whenReady` throws `Release build requested but no signing config is present.` whenever a `assemble/bundle/package*Release*` task runs without a `storeFile` |
| release build type | `isMinifyEnabled = false`, `isShrinkResources = false` (R8/reflection was a past casualty) |
| lint | `abortOnError=false`, `checkReleaseBuilds=false`, `ignoreWarnings=true`, `warningsAsErrors=false`, `baseline=file("lint-baseline.xml")`, `MissingPermission`/`NewApi`/`LocalContextGetResourceValueCall`/`QueryAllPackagesPermission` disabled |
| version defaults | `RETURNGIFT_VERSION_CODE` env/`local.properties` → fallback `30000`; name fallback `3.0.0` |
| output naming | release → `ReturnGift-release.apk`; debug → `ReturnGift_v<version>_<yyyyMMdd_HHmmss>.apk`; ABI-filter aware (splits disabled — universal APK) |
| unit tests | `testOptions.unitTests.isReturnDefaultValues = true` (the "not mocked" fix) |
| Kotlin | **no `org.jetbrains.kotlin.android` plugin** — AGP 9 built-in Kotlin; `org.jetbrains.kotlin.plugin.compose` 2.0.21 |

### Toolchain (verified)
Gradle wrapper **9.7.0-all** · AGP **9.3.1** · Kotlin **2.0.21** · compose plugin 2.0.21 · `gradle.properties`: `-Xmx4096m`, `kotlin.incremental=false`, `kotlin.caching.enabled=false`.

### Workflow ledger (Release APK, public API)
- **Runs #55–#79 → 25/25 FAILED**, all at step **"Build signed release APK"**; every run that still had a Quality Gate (through v3.0.9) had it **green**.
- Last successful release: **v2.6.0-20260823084102** (Aug 23, commit `ed7df951`; APK autopsy: `v=2.6.0 c=ed7df951… b=runner`, AGP 9.3.1, 4 ABIs, `liblitertlm_jni.so`+`libmmkv.so` packaged, v2/v3-signed, SHA256 matched published `SHA256SUMS.txt`).
- Storage-cleanup workflow was **fixed by the project** (keep=30 + `Never purge release workflow runs - they contain release evidence`) — the old evidence-destroying config is gone.

---

## 3. The diagnostic checklist, rewritten for THIS project

Every command below is what the project actually needs. Run them **in this order** — each one answers a specific question. Use `./gradlew` (wrapper) always, never a global Gradle.

### Step 0 — Before ANY diagnosis: get the real error (60 seconds)
The workflow already **writes the last 200 lines into the run summary** and uploads the full log as an artifact. You own the repo, so:
```bash
gh run view 33861127104 --log-failed > err.txt    # run #79 / tag v3.0.12
# or: GitHub web → Actions → run #79 → step "Build signed release APK" (summary shows the 200-line tail)
# or: Artifacts → assemble-release-log → download and read the END of the file
grep -n "e: file" err.txt | head          # Kotlin/Java compile errors
grep -n "> Task" err.txt | head           # the task that died
grep -n "Caused by" err.txt | head        # the FIRST cause, not the last line
grep -n "Building with keystore type" err.txt   # production vs ci-fallback
```
**The first `> Task :app:… FAILED` + the first `Caused by:`/`e: file:` line ARE the answer.** Never diagnose from the final `BUILD FAILED`.

### Step 1 — Environment sanity (5 seconds each)
```bash
java -version                          # MUST be 17.x — project compiles to 17; JDK 11/8 = instant fail
./gradlew --version                    # MUST say Gradle 9.7.0 — never a global Gradle
git --version && git rev-parse HEAD    # config-time git calls; no git = every task dies
sdkmanager --list_installed 2>/dev/null | grep -E "platforms;android-37|build-tools|ndk;27" 
```
Expected at HEAD: `android-37` (+ minor 1), NDK **27.0.12077973**, JDK 17, Gradle 9.7.0, AGP 9.3.1, Kotlin 2.0.21. **Compatibility chain (don't mix):** JDK 17 ↔ Gradle 9.7 ↔ AGP 9.3.1 ↔ compileSdk 37.1 ↔ NDK 27.0.12077973.

### Step 2 — The free local gate the project is NOT using (run this before every push/tag; takes ~2 s, needs no SDK)
```bash
bash scripts/ci-preflight.sh           # 12 guards + lexer (324 files) — this passed at HEAD
python3 scripts/kotlin-structure-check.py   # braces/strings/block-comments lexer (same as in preflight)
```
⚠️ **What these CANNOT catch** (that's the whole story of v3.0.9–v3.0.12):
`new ProvenanceTag(kind, origin)`, `ProvenanceHelper.addToFrontmatter(...)` vs `.INSTANCE.`, missing `import ClawApplication`, `androidx.compose.runtime.OptIn`, wrong-arity calls. Those need a compiler.

### Step 3 — Clean (only when you suspect stale state; it's not the current cause)
```bash
./gradlew clean
```
Do **not** routinely `rm -rf .gradle build app/build` — the project deliberately reuses caches now (`913ccf4` removed the purge to save minutes). Only do the nuke if a cache restore is suspected. Don't use `--refresh-dependencies` routinely.

### Step 4 — Compile the RELEASE variant SEPARATELY (the missing gate; 2–4 min instead of 6+)
```bash
./gradlew :app:compileReleaseKotlin --stacktrace
./gradlew :app:compileReleaseJavaWithJavac --stacktrace
```
This is the single most important command in this whole document: **it reproduces Layer-1 failures without signing, without packaging, without NDK.** Debug green ≠ release green (proven by the saga). If these pass locally, then and only then:
```bash
./gradlew :app:assembleRelease --stacktrace --info        # Layer 2/3 appear here
```

### Step 5 — Signing, checked BEFORE the release build (Layer 2)
```bash
./gradlew :app:signingReport
```
Expected at HEAD: `Variant: release … Config: release … Store: <path>` — if `storeFile = null` (or the whenReady gate fires) you are missing `KEYSTORE_FILE` etc. Verify the actual keystore independently:
```bash
keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD"   # alias + valid
base64 -w 0 keystore.jks        # for ANDROID_KEYSTORE_B64 — NO line breaks
```
⚠️ Project-specific gotcha (`RELEASING.md` + workflow): secrets **missing** ⇒ build *passes* with the CI fallback key (but the APK then **cannot upgrade over production APKs**); secrets **present but wrong/corrupt** ⇒ `Keystore was tampered, or password was incorrect` and the fallback never engages. Check the build log's first line: `Building with keystore type: production | ci-fallback`. **Never hard-code passwords in Git / `build.gradle.kts`** — env or `local.properties` only (both gitignored).

### Step 6 — inspect the APK after a SUCCESSFUL build (not just "BUILD SUCCESSFUL")
```bash
find . -name "*.apk"          # expect: app/build/outputs/apk/release/ReturnGift-release.apk
unzip -p app/build/outputs/apk/release/ReturnGift-release.apk assets/build_fingerprint.txt | xxd -r -p
#   → v=<version>  c=<tag commit sha>  b=runner   (the APK's birth certificate)
unzip -l app/build/outputs/apk/release/ReturnGift-release.apk | grep 'lib/.*/' | cut -d/ -f2 | sort -u
#   → arm64-v8a armeabi-v7a x86 x86_64  (universal, no splits)
apksigner verify --print-certs app/build/outputs/apk/release/ReturnGift-release.apk   # v2/v3 + signer CN
sha256sum app/build/outputs/apk/release/*.apk    # publish + re-compare after download
```

### Step 7 — Test BOTH variants, always
```bash
./gradlew :app:assembleDebug           # debug path
./gradlew :app:assembleRelease         # release path (different interop + signing + packaging)
```
Never conclude "it works" from debug only — that is precisely the mistake that produced 25 red runs.

### Step 8 — R8/minification check (already ruled out at HEAD, keep it that way)
Release has `isMinifyEnabled = false` / `isShrinkResources = false` (set in `5607015` after R8 broke reflective tool registration). If you ever re-enable shrink, first add keep rules; do not leave it off "forever" silently — document it.

### Step 9 — Get the EXACT failing task
```bash
./gradlew :app:assembleRelease --stacktrace --info | tee build.log
grep -E "> Task .* FAILED|e: file|Caused by:" build.log | head -20
```
Focus on the first failing task: `:app:compileReleaseKotlin` / `:app:compileReleaseJavaWithJavac` / `:app:packageRelease` / `:app:stripReleaseDebugSymbols` / `:app:lintVitalRelease`. Layer 1-errors show as `e: file:///...`.

### Step 10 — Offline mode (dependency/network question)
```bash
./gradlew :app:assembleRelease --offline
```
If it passes offline but fails online → flaky dependency resolution; if it fails offline with missing artifacts → first run needs network (the CI cache key `gradle-v3-<hash>` restores dependencies from `~/.gradle/caches`, so only the very first run downloads everything).

### Step 11 — Slow vs broken (not the current issue, but for completeness)
```bash
./gradlew :app:assembleRelease --profile    # top-level timings
```
Everything about the recent failures is fast → they're hard errors, not hangs.

### Step 12 — Dependency conflicts (only if you see version warnings)
```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencyInsight --dependency <lib> --configuration releaseRuntimeClasspath
```

### Step 13 — Daemon stuff (only when diagnosing, not as a "fix")
`--no-daemon` is for diagnosing daemon/environment issues. It is not a fix, and with `kotlin.incremental=false` the project already avoids incremental state.

---

## 4. The decision tree — read one line, know the layer

| Line in the log (first match wins) | Layer | Cause | Action |
|---|---|---|---|
| `> Task :app:compileReleaseKotlin FAILED` + `e: file:///…` | 1 | release Kotlin compile error | Compile locally first (Step 4); fix the specific unresolved ref/import. |
| `> Task :app:compileReleaseJavaWithJavac FAILED` | 1 | Java→Kotlin interop | `object` → `INSTANCE.`; pass ALL args (no Kotlin defaults); `import ClawApplication`; no `kotlin.Result`-returning calls from Java (use `*FromJava` wrapper). See AGENTS.md pitfalls 4b/6/7/8. |
| `Keystore was tampered, or password was incorrect` | 2 | secrets present but wrong | Re-export secrets (`base64 -w 0`); verify with `keytool -list`. |
| `Failed to read key …` / `SigningConfig "release" is missing required property "storeFile"` | 2 | secrets missing/not read | Set env (`KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD`) or `local.properties` (root and/or `app/`). |
| `Release build requested but no signing config is present.` | 2 | the `whenReady` gate | same as above. |
| `Failed to find NDK` / `NDK did not have a source.properties` | 3 | NDK not present | `sdkmanager "ndk;27.0.12077973"` (or align `ndkVersion` with what `setup-android` installed). |
| `Lint found fatal errors` (via `lintVitalRelease`) | 3 | lint vital | Add `-x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease`; config already suppresses most. |
| `> Task :app:packageRelease FAILED` | 3 | packaging/signing | read its own `Caused by:` (signing, zipflinger, duplicate files…). |
| `Building with keystore type: ci-fallback` and build GREEN | — | silent upgrade-incompatibility | Set real secrets if this APK must upgrade over production. |

---

## 5. What to change (short, actionable list)

1. **Break the blind loop — compile the release variant cheaply BEFORE tagging.**
   - Locally: run Step 4 (`:app:compileReleaseKotlin`, `:app:compileReleaseJavaWithJavac`) — no keystore/NDK needed to compile.
   - In CI: add these two tasks (or `assembleDebug -x lint`) as a pre-tag job, OR restore `testDebugUnitTest` as a gate and add `compileReleaseKotlin` to it. Even better: append them to `scripts/ci-preflight.sh`'s "next" stage — a `gradle`-based preflight that runs `assembleDebug` + `compileReleaseKotlin` + `compileReleaseJavaWithJavac` once a Java 17 toolchain is available. **10–15 s of local preflight would have eliminated 25 failed runs.**
2. **Follow AGENTS.md pitfalls 1–8 as law** (they are the exact post-mortem of this saga; 6 of 8 are preflight-enforced; 3/6/7/8 are compile-only and need a compiler, not grep).
3. **Re-verify the v3.0.12 HEAD source locally once** with a real `compileReleaseKotlin` — `65efb7a` ("remove unreachable InterruptedException catch") was the last fix and its "unreachable catch" is exactly the class of thing a compiler reports but grep sees nothing of.
4. **Signing secrets**: keep the 4 GitHub secrets in sync with the real keystore; if the production key is lost, decide consciously: CI fallback (fresh installs only) and document it in RELEASING.md.
5. **Tag discipline**: one `v*` tag per release; never recreate/delete-and-retag the same version (each retag = one more CI run). Tags v3.0.2–v3.0.8 were already deleted from remote (only v3.0.0, v3.0.1, v3.0.9–v3.0.12 remain) — keep version numbers strictly increasing.
6. **Stop "fixing compile" via tag-churn**: fix → compile locally → then tag. A tag is a release decision, not a debug build.

---

## 6. The protocol for every build (paste this into the agent session / append to AGENTS.md)

```
MANDATORY before EVERY build or release of ReturnGift:

1. PREFLIGHT (no SDK needed): bash scripts/ci-preflight.sh  → must end
   "CI pre-flight: no known pitfalls detected."
2. COMPILE release FIRST: ./gradlew :app:compileReleaseKotlin
   and ./gradlew :app:compileReleaseJavaWithJavac (JDK 17, Gradle 9.7.0 wrapper).
   If these fail, FIX THE COMPILE ERROR and re-run. Do NOT tag. Ever.
3. THEN: ./gradlew :app:assembleDebug, then :app:assembleRelease --stacktrace --info.
4. AUTOPSY the APK: fingerprint (v/c/b), META-INF/version-control-info.textproto
   (revision == tag commit), exactly 4 ABIs, apksigner verify --print-certs
   (v2/v3 + CN), sha256sum == published SHA256SUMS.txt.
5. RELEASE: add '### vX.Y.Z' to README → push main → create ONE tag vX.Y.Z →
   confirm ONE workflow run, "Building with keystore type: production",
   Quality Gate green (if present), Build & Release green, assets attached.
6. KNOW THE PITFALLS (AGENTS.md 1–8): no ic_menu_compose; no return in default
   args; no bare this in coroutines; explicit imports in tv/* Java tools; no
   kotlin.Result across Java boundary (*FromJava wrapper); Java must pass every
   arg & use object.INSTANCE & import ClawApplication; @OptIn comes from Kotlin
   (never androidx.compose.runtime.OptIn); verify SDK methods against AOSP.
7. NEVER: edit logic to silence a compile error (no emptyMap() stand-ins);
   use system Gradle; rotate the keystore; delete release run evidence; tag
   twice for one version; conclude success from BUILD SUCCESSFUL without the
   APK autopsy; paste only the last 20 log lines when asking for help — give
   java -version, ./gradlew --version, AGP/Kotlin versions, compileSdk/minSdk/
   targetSdk, ndkVersion, the exact command, the FIRST 'Caused by:', the failing
   task, and 50–100 lines around the first error.
```

## 7. Definition of done (tick all)

- [ ] `ci-preflight.sh` green locally (free, 2 s)
- [ ] `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` green locally (the missing gate)
- [ ] `:app:assembleDebug` and `:app:assembleRelease` green locally
- [ ] `signingReport` shows the intended config; `keytool -list` succeeds; CI log says `production` keystore
- [ ] APK autopsy: correct `v=`/`c=`/`b=`, revision == tag, 4 ABIs, v2/v3, checksums match
- [ ] exactly one tag, one workflow run, one `ReturnGift-release.apk` + `SHA256SUMS.txt` released
- [ ] README changelog entry `### vX.Y.Z` exists

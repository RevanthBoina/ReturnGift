# ReturnGift — repository audit

Date: 2026-10-25 · Commit: `7acea8c` (v3.5.1) · Method: static analysis of the checkout,
no Android SDK available here, so nothing was compiled or run in this pass beyond the
numbers below.

**Read this first:** the deliverable is not "here is a summary of your app". It is
*what is most likely to hurt you next*, in order, with the evidence that justifies it.

---

## 1. Snapshot (measured, not estimated)

| Fact | Value |
|---|---|
| Kotlin sources (main) | 273 files, 59,136 lines |
| Java sources (main) | 60 files |
| Test files | 74 (73 pure JVM, 8 Robolectric) |
| Assertions in tests | 2,055 |
| Largest file | `ui/chat/ChatScreen.kt` — 3,791 lines, 62 composables |
| Second largest | `agent/DefaultAgentService.kt` — 2,084 lines |
| Registered tools | 44 (29 implementation files) |
| YAML skills | 19 |
| Release APK | 141,925,575 bytes (135.3 MiB), universal ABI |
| `minify` / `shrinkResources` (release) | **false / false** |
| Manifest permissions | 19 |
| `Thread.sleep` sites | 62 |
| Swallowed-exception sites (`catch (_: Exception)`, `runCatching{}.getOrNull()`) | 72 |
| TODO/FIXME/HACK | 5 |
| Hardcoded cloud keys | none found (clean) |

Versioning is consistent: gradle `3.5.1` / `30501` matches release tag `v3.5.1`.

*Limitation:* this is a shallow clone (1 commit), so churn, ownership and "how often does
this file change" — the best predictors of where bugs live — are unavailable here.

---

## 2. What is genuinely good (and uncommon)

I want to be specific, because these are the things a rewrite would destroy:

1. **Security defaults are right at the platform level.** `allowBackup="false"`,
   `network_security_config` with `cleartextTrafficPermitted="false"` as base and only
   `.local/.lan/.internal` exempt, `dataExtractionRules` declared. Plenty of shipping apps
   get this wrong.
2. **A real test corpus, not decorative.** 74 files, 2,055 assertions, 73 of them pure
   JVM (no emulator needed). The plan/MCP/routing work landed with 248 of them.
3. **Determinism where it matters.** `agent/plan`, `agent/routing`, `agent/mcp` policy are
   pure functions of data — no LLM call to plan, no network in the decision. That is why
   they could be tested at all.
4. **Repo rules are enforced, not documented.** `scripts/check-app-agnostic.py` fails the
   build on a per-app file; `ci-preflight.sh` encodes past compile mistakes. This is the
   single best predictor of long-term health in this repo.
5. **No secrets in source.** Grep for `sk-`, `AIza`, `ghp_` patterns: zero hits.

---

## 3. Findings, by what I would fix first

### P0 — Nothing compiles or tests a pull request

All five workflows:

| Workflow | Trigger | Runs unit tests |
|---|---|---|
| `release.yml` | tag push | **no** (0 references to `testDebugUnitTest`) |
| `auto_build_and_test.yml` | `workflow_dispatch` only | yes — but never fires automatically |
| `build.yml` | `workflow_dispatch` only | yes — but never fires automatically |
| `emulator-matrix.yml` | `workflow_dispatch` only | smoke |
| `storage-cleanup.yml` | cron | no |

So the only automatic trigger in the entire repo is a **tag push**, and that path does not
run tests.

This is not theoretical. It happened twice in one afternoon:

- PRs #106/#107 merged into `main` with two compile errors that nobody could have seen.
- Tag `v3.5.1` then failed in `:app:compileReleaseKotlin` with:
  `Unresolved reference 'MAX_ATTEMPTS'` and `McpToolProxy is not abstract and does not
  implement abstract base class members getDescriptionEN/getDescriptionCN`.
- Both were fixed in PR #108 and re-tagged — but the release almost died to a missing
  `private const val`.

**Why it is P0 rather than an annoyance:** a repo whose default branch is never compiled
turns every merge into a bet. It also means the 74 test files have **no evidence of being
green in this state** — locally I ran 248 of them through a purpose-built harness, which
proves those tests' logic, not that `./gradlew testDebugUnitTest` passes.

**Fix (half a day):** one `ci.yml` on `pull_request` + `push: branches: [main]` running
`bash scripts/ci-preflight.sh` then `./gradlew :app:compileDebugKotlin testDebugUnitTest`.
And add `testDebugUnitTest` to `release.yml` **before** `assembleRelease`, so a tag can
never ship untested code.

---

### P1 — The vault protects the jewellery box and leaves the safe open

MCP bearer tokens are now AES-GCM under an `AndroidKeyStore` key. The **cloud provider API
keys — the highest-value secret in the app** — are still plaintext MMKV:

```
utils/KVUtils.kt:47   MMKV.initialize(context)
utils/KVUtils.kt:48   mmkv = MMKV.defaultMMKV()
utils/KVUtils.kt:298  fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
utils/KVUtils.kt:302  fun getApiKeyForProvider(provider: String): String
```

`EncryptedSharedPreferences` appears in exactly two places (`dev/DevConfig.kt`,
`server/CloudDeepAgentService.kt`) and not on that path.

Mitigations already in place: `allowBackup="false"` (so no cloud backup exfiltration) and
the file is app-private (root/`run-as` only). But a rooted device, a debuggable build, or
a bugreport dump yields **paid, billable** keys — a worse outcome than a stale MCP token.

**Fix:** generalise the seam that now exists. `McpSecretStore` is already
cipher-agnostic; rename to `SecretStore` (or add `ApiKeyStore` delegating to it), migrate
existing values on first read, and keep the fail-closed behaviour. The expensive part
(`KeystoreSecretCipher`, migration pattern, tests) is already written and proven — this is
mostly a call-site change in `KVUtils` + `LlmConfigActivity` + `RoutedLlmClient.credentialFor`.

---

### P1 — The chat + agent-loop layer is four files doing one job

| File | Lines | Role |
|---|---|---|
| `ui/chat/ChatScreen.kt` | 3,791 | 62 composables |
| `agent/DefaultAgentService.kt` | 2,084 | the loop + 10 guards + tools + persistence |
| `ui/chat/TaskFlowController.kt` | 863 | task flow |
| `TaskOrchestrator.kt` | 860 | task entry/orchestration |
| `ui/chat/ChatSessionController.kt` | 831 | sessions |
| `ui/chat/ComposeChatActivity.kt` | 557 | host |

~9k lines across the surface where every user-visible change lands. `DefaultAgentService`
alone contains: intent gate, plan-first, plan contract, consent gate, target-spec gate,
allow-list, pre-action judge, action verification, watchdog recovery, exec budget,
artifact contract, undo, tracking, finish handling.

Not a bug — it compiles and ships. But it is the highest-regression-risk region in the
repo, and it is where the newest, most safety-critical logic (the plan contract) just
landed. Every one of those guards is order-dependent, and the order is enforced only by
line position in a 2,000-line function.

**Fix, incrementally (no rewrite):** extract the loop into `agent/loop/AgentLoop.kt` that
takes a `LlmClient` + `ToolRegistry` + a list of `LoopGuard` interfaces and returns a
result. Each guard becomes a testable class (several already are). Then write tests that
feed a scripted LLM through it — you already have `ScriptedTransport` for MCP; the same
pattern works here. `DefaultAgentService` shrinks to wiring.

---

### P2 — 141.9 MB, universal, unminified

Three separate levers, in order of payoff:

1. **ABI filter.** `app/build.gradle.kts:92` already supports `RETURNGIFT_ABI`; the release
   build does not set it, so it ships every ABI. The comment in the file already says it:
   the on-device model runtime is native `.so` per ABI. `arm64-v8a` alone covers
   effectively every phone since ~2017 and should cut the download by roughly two thirds
   for the personal build. (Public release genuinely needs a universal/App Bundle build —
   that is what `bundleRelease` + Play is for, not a fat APK.)
2. **`isMinifyEnabled = false`** (release). Besides size, this leaves your new security
   logic — the trust policy, the redaction rules, the gate ordering — plainly readable to
   anyone who unzips the APK. For an app whose pitch is "autonomous agent with your data",
   that is a real consideration, not just aesthetics.
3. **Unused surface.** `AutoReplyManager.java` is 1,215 lines for WeChat automation, plus
   dingtalk/oapi SDKs and a nanohttpd config server. Each is size + attack surface +
   maintenance. Worth asking which of these are load-bearing today.

---

### P2 — Error handling hides its own cause

72 sites swallow exceptions (`catch (_: Exception)`, `runCatching { … }.getOrNull()`); 65
of them are `catch (_: Exception)`/`catch (_: Throwable)`. In a device-automation loop that
means a failed tap, a dead a11y service and a JSON parse error all become the same
`null` — which is then interpreted as "try again", burning iterations against the exec
budget.

**Fix:** one `Safe.run(tag) { }` helper that logs once with tag + message and returns null,
and a preflight grep forbidding bare `catch (_: Exception) { }`. Cheap, and it converts
silent retries into diagnosable ones.

Also: **62 `Thread.sleep` call sites**. Some are legitimate waits in the loop thread; any
inside a composable or on the main thread is jank or ANR. Worth one pass to classify them.

---

## 4. Order I would actually work in

| # | Work | Why first | Size |
|---|---|---|---|
| 1 | `ci.yml` on PR: preflight → `compileDebugKotlin` → `testDebugUnitTest`; add tests to `release.yml` | Removes the failure class that already bit you twice | ½ day |
| 2 | Run the full suite once in CI; fix or delete whatever is stale | You cannot claim "green" as a repo property until Gradle says so | ½–1 day |
| 3 | Move provider API keys onto the existing encrypted store | Highest-value secret, lowest effort (seam already built) | 1 day |
| 4 | Extract `AgentLoop` + scripted-LLM tests | Protects the region where every change lands | 2–3 days |
| 5 | `arm64` personal build + minify experiment on a branch | Size and tamper-resistance | 1 day |
| 6 | `Safe.run` sweep + preflight rule | Turns silent failures into logs | ½ day |

---

## 5. What I could not assess (be honest about the edges)

- **Runtime behaviour.** No emulator or device here: no measurement of task success rate,
  latency, battery, memory, or how often the guards fire in practice.
- **History.** Shallow clone; no churn, no author concentration, no "this file is rewritten
  every week" signal.
- **Dependencies' health.** `libs.versions.toml` versions were not checked against
  advisories.
- **The 74 tests' current status under Gradle.** Only 248 assertions-worth were executed
  here, via my own harness, not `./gradlew`.

---

## 6. One-line verdict

A genuinely well-instrumented agent with better safety instincts than most production
apps — undermined by the fact that **nothing runs on a pull request**, so its 59k lines
and 74 test files are only ever checked at the moment a release tag is pushed.

# MCP connections, plan-first execution, and in-app model routing

Status: implemented in v3.5.0; security + contract remediation in v3.5.1
Scope: Android app only. No server, no new dependency, no per-app files.

This document explains three additions, what they were modelled on, what was
deliberately **not** copied, and what they cost in APK size.

---

## 1. Plan-first execution (`agent/plan`)

### The requirement

> The AI should first understand and plan the next steps as per the prompt. If the
> user asks "Follow youtube on LinkedIn" it should provide a clean step-by-step plan:
> Open LinkedIn → Search "YouTube" → Filter by Companies → Select official YouTube
> page → Tap "Follow". Applicable for every prompt when required, and ask questions
> for clarification.

### Design

```
user text
   │
   ├─ IntentParser      deterministic grammar → TaskIntent (capability + entities)
   ├─ PlanContext       installed apps + guide registry (labels, surfaces, aliases)
   ├─ PlanComposer      intent + data → ordered PlanSteps
   ├─ PlanClarifier     answers → clarified sentence → re-plan
   └─ PlanFirst         mode (AUTO / PREVIEW / SILENT) + presentation decision
```

Everything is a pure function of (text, context). Consequences that mattered:

- **No model call** to produce the plan: works on the local-model path, costs zero
  tokens, is unit-tested, and cannot hallucinate an app that is not installed.
- **App-agnostic**: the plan names apps from `PlanContext` only. `PlanFirstTest`
  proves it by rerunning the same prompt against renamed apps and asserting the
  plan changes accordingly (and that no app name is hard-coded in the output).
- **Clarify instead of guess**: a missing app, missing recipient, missing text or a
  risky verb produces a `PlanQuestion`. Answers are folded back into the sentence
  (`follow youtube` + `LinkedIn` → `follow youtube on LinkedIn`) and the plan is
  rebuilt — so the second plan cannot keep a stale assumption.

### When a plan is shown

| Situation                        | Behaviour                                  |
|----------------------------------|--------------------------------------------|
| ≥2 steps needed                  | plan shown, execution continues             |
| single step (e.g. "open X")      | no plan (noise), execution continues        |
| knowledge/vault question         | no plan, answered directly                  |
| blocking question                | **stops**, asks up to 3 questions           |
| "show me the plan" / "plan only" | plan shown, execution **stops**             |
| "just do it" / "no need to ask"  | plan hidden, required questions still asked |
| irreversible verb (delete/pay/…) | approval question before acting             |

### The headline case, verbatim in tests

```
Follow youtube on linkedin
 → Open LinkedIn
 → Search "YouTube"
 → Filter by Companies
 → Open the official YouTube page
 → Tap Follow
 → Verify the button shows Following
```

Entity type drives the filter step: a single token or an organisation marker
(`labs`, `inc`, `tv`, …) → **Companies**; two or three capitalised words → **People**.

---

## 2. MCP connections (`agent/mcp`)

### What was studied

| Source | What it contributed | What was rejected |
|---|---|---|
| [openai/mcp-extensions](https://github.com/openai/mcp-extensions) | Forward-compatible capability negotiation: servers advertise `capabilities["extensions"]` on the new spec (`server/discover`) and legacy `capabilities["experimental"]` on `initialize`; unknown capabilities, content types and notifications must be ignored, not fatal. | The ChatGPT-specific UI surface (sidebar/thread/file entrypoints, `openai/settings`, `ui/message`). Irrelevant on Android — we have no ChatGPT shell to extend. |
| [rmyndharis/OpenWA](https://github.com/rmyndharis/OpenWA) | The shape of a connection registry: API-key auth, per-session scoping, audit trail, honest connection state. | The server architecture itself (Node + SQLite/Postgres + Redis + Docker + webhooks). An APK is the client, not the gateway. |
| [aden-hive/hive](https://github.com/aden-hive/hive) | "One execution primitive" and *bounded* fan-out: a narrow tool set per unit of work, a tracker that survives crashes, budgets per worker. | Queen/worker colonies and playbooks — ReturnGift runs one task at a time on one device; the useful part is the **bounded tool surface**, which we implement as a ranked, capped tool index. |

### Protocol subset implemented

```
initialize                → protocolVersion, serverInfo, capabilities (+ extensions/experimental)
notifications/initialized → (no reply)
tools/list (+cursor)      → tool definitions, capped at 48, ≤8 pages
tools/call                → content blocks: text, image, resource_link, resource, unknown
resources/read            → text payload (used as an app-knowledge source)
```

Transport: **Streamable HTTP only** (`Mcp-Session-Id`, `MCP-Protocol-Version`,
`Accept: application/json, text/event-stream`). Responses are accepted as a single
JSON object, as SSE frames, or as concatenated objects (some gateways).

**Why no stdio transport:** Android cannot spawn arbitrary MCP servers; there is no
`npx`/`uvx` at runtime and no guarantee of an executable. A stdio bridge would need a
bundled binary per server, which is exactly the bloat this design avoids.

### Failure policy

- Auth (401/403) → `McpAuthException`, server skipped, everything else keeps working.
- Session expired (404 after a session existed) → re-`initialize` once, retry once.
- Server down / 5xx / timeout → logged, previously discovered tools retained.
- Malformed payload → ignored with a log line; the call returns what it can.
- No server can ever block a task: refresh happens on a background thread at startup
  and is wrapped in `runCatching`.

### Knowledge through MCP

`McpKnowledgeBridge` turns an MCP resource into an `AppKnowledgeSource` (the same
`ConnectorKnowledgeSource` the knowledge layer already tests). A server can therefore
ship app guides without an app release, and connector rows outrank built-ins for the
same package.

### Reaching it from the UI

`Settings → MCP connections`: list, enable/disable, remove, add (name, URL, optional
bearer token), and a "Refresh" action that reports how many tools each server
actually exposed.

---

## 3. In-app model routing (`agent/routing`)

### Why not just call OmniRoute

OmniRoute is a gateway: a Node server with a SQLite database, ~375 providers and
1.9 GB of repository history. Pointing the APK at it means the user must run or
reach a server, and the device loses control over routing, quota and privacy.

The interesting part of OmniRoute is not its size — it is four ideas:

1. **Capability/cost-aware selection** (their "auto" combos),
2. **Quota-aware fallback** with per-account cooldowns,
3. **Dual-window quota accounting** (short sprint + long baseline),
4. **Explainable decisions** (`RoutingEvent`, decision traces).

All four are cheap to reimplement on-device. The 375 providers, compression engines
(RTK/Caveman) and shadow-traffic evaluation are not.

### How OmniRoute actually connects Antigravity (and what transfers)

Reading `bin/antigravity-bridge.mjs` and `docs/guides/ANTIGRAVITY-ONBOARDING.md`:

- Both the Antigravity IDE and the `agy` CLI talk to Google's **Cloud Code API**
  (`cloudcode-pa.googleapis.com`), with the same OAuth client, refresh token and
  Google account. Every connection needs a `projectId`, discovered via
  `loadCodeAssist` (and created with `onboardUser` when missing).
- The "bridge" is a **local HTTP proxy** the CLI is pointed at. It intercepts only
  *streaming generation* calls whose model is **not** a native Gemini/Cloud-Code
  model, rewrites the model id to a routed target, and forwards the request to
  OmniRoute `/v1/antigravity` with the router key. Everything else is passed through
  to Google with the CLI's own token.
- Quota is **dual-layer**: a 5-hour sprint pool refreshed on first use, plus a 7-day
  baseline that overrides it; Gemini models share one pool, non-Gemini models have
  separate limits. Accounts are independent, so fallback across accounts raises the
  ceiling.

What we took: the **selection + quota + fallback** model (implemented in
`ProviderRouter` and `TokenLedger`). What we did not take: proxying another app's
traffic (requires host override/MITM and breaks as soon as the app pins
certificates), and OAuth against Cloud Code (against Google's terms, and the tokens
belong to another product).

### Implemented pieces

| File | Responsibility |
|---|---|
| `ProviderCatalog.kt` | Reads models/prices from the existing `CloudProvider` (single source of truth) and adds routing traits: quality, latency, tools/vision. ~9 model rows. |
| `TokenLedger.kt` | Per-provider tokens + cost with a 5-hour and a 7-day window, budgets, and cooldowns. Injectable clock → rollover is unit-tested. |
| `ProviderRouter.kt` | Filters (context, tools, vision, allow-list, exhausted, cooling), scores per strategy (`AUTO`, `CHEAPEST`, `FASTEST`, `QUALITY`, `LOCAL_FIRST`), returns an ordered chain plus a human-readable trace. Classifies failures (rate limit / quota / auth / transient / fatal) and applies cooldowns. |
| `RoutingLedger.kt` | Process-wide ledger + which provider keys the device holds (installed at startup from the per-provider key slots). |
| `llm/RoutedLlmClient.kt` | Executes the chain against real clients, falls back on retryable failures, records tokens/cost and data-egress rows with the **actual** provider. |

Routing is used when the configured provider is **OmniRoute with model `auto`** and
the device holds at least one provider key. Otherwise the existing gateway client is
used unchanged, so a working setup keeps working.

---

## 4. What it costs in the APK

Measured, not estimated from source size: the new pure-Kotlin sources were compiled
with the Kotlin 2.4.21 compiler and the resulting `.class` files summed (release
build has `isMinifyEnabled = false`, so dex ≈ class bytes; the constants dedupe that
dex performs typically removes ~10%).

| Module | Classes | Class bytes | KiB |
|---|---:|---:|---:|
| `agent/plan` (plan-first) | 27 | 144,202 | 140.8 |
| `agent/mcp` (client + store + index + bridge) | 39 | 188,353 | 183.9 |
| `agent/routing` (catalog, ledger, router) | 23 | 114,467 | 111.8 |
| **Total** | **89** | **447,022** | **436.5** |

- v3.4.0 APK: **141,597,867 B**. New bytecode: **0.316 %** of the APK.
- Dex estimate: **≈ 390–440 KiB** (`isMinifyEnabled = false` today; enabling R8 would
  cut this by roughly half).
- **Zero new dependencies**: no vendor SDKs, no HTTP library (the transport uses
  `HttpURLConnection`), no JSON library (a ~200-line `McpJson` keeps the protocol
  layer dependency-free and testable), no bundled Node/Python runtime.
- Not counted: the settings screen (`McpServersActivity`, Compose — already on the
  classpath) and `PlanContextFactory` (Android glue), both a few KiB.

For comparison, the gateway this replaces is a Node.js service with a SQLite
database and a 1.9 GB repository; the minimum honest equivalent on-device is what
the table above shows.

---

## 5. Operating it

**Add a provider for routing** — Settings → Model: save a key per provider. The
router only considers providers with a key; with none, `auto` keeps using the
gateway.

**Add an MCP server** — Settings → MCP connections → name, URL (`https://…/mcp`),
optional bearer token. Tools appear as `mcp__<server>__<tool>` and are ranked per
task, capped at 12 in the prompt.

**See why a model was chosen** — `RoutingLedger.explain(decision)` returns the trace
(`excluded gpt-4.1 (no vision)`, `chose … (score=0.42, ~$0.0012)`); it is logged at
`RoutedLlmClient` on every request.

---

## 6. Explicit non-goals

- Hosting an MCP **server** inside the APK.
- stdio / SSE-long-lived transports (single request/response over HTTP only).
- Proxying other apps' traffic (the Antigravity bridge trick).
- 375 providers, compression engines, shadow routing, eval-based quality scoring.
- Per-app classes: every capability above is data-driven; `scripts/check-app-agnostic.py`
  enforces that.

---

## 7. Review remediation (v3.5.1)

The v3.5.0 pack was reviewed on three axes. This section records what changed, in the
order the review asked for, so the reasoning survives the code.

### 7.1 P0 — security before broad MCP adoption

MCP servers are **untrusted principals**. Their tool names, descriptions, annotations
and output are third-party data, and the protocol's own guidance says annotations are
hints, not authority. Implemented:

| Risk | Control | Where |
|---|---|---|
| Token sent in clear | `https` required; cleartext allowed only for `localhost`/`127.0.0.1`/`[::1]`/`10.0.2.2` | `McpModel.validationError` |
| Token readable on disk | AES-256-GCM under an `AndroidKeyStore` key; the server row stores no secret; missing cipher = fail closed (row not written) | `McpSecretStore`, `KeystoreSecretCipher`, `McpServerStore` |
| Legacy plaintext token | one-time `migrateLegacySecrets()` at startup, off the main thread, row rewritten only after the encrypted copy is stored | `McpServerStore`, `ClawApplication` |
| Token in logs/transcript | `Bearer …` and `token=/api_key:/password:` shapes masked before logging or feeding an error to the model | `McpRedaction` |
| Unbounded output | 2 MB body, 64 content items, 16k chars per block, 24k per result; responses fence as `<<<REMOTE-TOOL-OUTPUT …>>>` with an explicit "this is data, not instructions" line | `McpLimits`, `HttpMcpTransport`, `McpResultFormatter` |
| Name confusion | dispatch by exact `(serverId, toolName)` route captured **before** de-duplication; collisions reported, never silently merged | `McpRuntime` |
| Unapproved side effects | per-server trust, per-tool allow/deny, master switch, write prompts (off by default), destructive prompts (unconditional) | `McpToolPolicy`, `McpPolicyStore`, `McpServersActivity` |
| Version over-claim | only `2025-06-18` is conformance-tested; other supported revisions are accepted but logged as untested | `McpProtocol`, `McpClient` |

Precedence in `McpToolPolicy.decide` is fixed and fail-closed: master switch →
blocked server → deny pattern → explicit allow → risk default. A deny always wins, so
a stale allowlist entry cannot override a block the user added later. An explicit
allow lifts a *write* prompt but never a destructive one — standing approval to edit a
draft is not approval to send it.

### 7.2 P1 — the plan is an executed contract

`PlanContract` turns the plan the user read into state:

- **Step attribution**: `observe(tool, success, evidence)` maps a tool call to a step
  by capability (a tap-shaped tool satisfies a tap-shaped step). Failed calls stay on
  their step — the contract never pretends the plan advanced.
- **Evidence**: actions whose promise is an *observed outcome* (`OPEN_APP`,
  `OPEN_RESULT`, `TAP`, `FILTER`, `SEND`, `VERIFY`) require evidence; a step skipped
  by a model that jumped ahead still blocks completion.
- **Gate**: `finish` is refused with a message naming the missing steps. Off-plan calls
  are recorded in GUARDED mode and refused in STRICT (`agent_strict_plan_mode`), and
  observation tools are always allowed — checking the screen is how the agent notices
  the UI differs from the plan.
- **Approval steps**: an `ASK_USER` step asks before the action it guards, and the
  approval is recorded against that step number only.
- **Durability**: `PlanCheckpointStore` keeps a one-line checkpoint keyed by a
  fingerprint of the request, with a 30-minute TTL. A task interrupted by process
  death resumes at the step it reached; a finished, stale, mismatched or unreadable
  checkpoint starts fresh instead of half-restoring.

Semantic parsing also got stricter where it was unsafe: quoted spans are content (they
are masked before a platform is resolved from `on <app>`), a clause that *forbids* an
action is skipped rather than inverted, and multi-clause requests plan the first
actionable clause while the full sentence still reaches the model.

### 7.3 P1 — routing truthfulness

- `RequestProfiler` derives `needsTools` and `needsVision` from the payload and counts
  **everything serialised**: system prompt, user/assistant turns, tool results, tool
  schemas (+12 tokens each) and images (800 tokens each), with a 1.15 safety margin and
  the completion reserve.
- `ContextTrimmer` drops the oldest droppable messages (never the system prompt, never
  the newest turn, never the latest user instruction) when the prompt would not fit the
  largest known window, and re-measures the result before routing.
- `RoutedLlmClient` uses the trimmed facts for both routing and the ledger, and the
  provider allowlist is honoured (`Request.providers`), so a provider the user has not
  configured is never silently called.

### 7.4 Verification and honest status

| Suite | Tests | Result |
|---|---|---|
| Plan (`PlanFirstTest`, `PlanClarifierTest`, `PlanContractTest`, `PlanCheckpointStoreTest`, `PlanAmbiguityTest`) | 89 | green |
| MCP (`McpClientTest`, `McpRuntimeTest`, `McpSecurityTest`, `McpPolicyTest`) | 94 | green |
| Routing (`ProviderRouterTest`, `RequestProfilerTest`, `ContextTrimmerTest`) | 65 | green |

These were compiled and run on a plain JVM with a real Kotlin compiler and a minimal
JUnit shim; `scripts/kotlin-structure-check.py` and `scripts/ci-preflight.sh` are clean.
**No Android assemble was run here** (no SDK in this environment), so the UI wiring
(`McpServersActivity`, `ClawApplication`, `DefaultAgentService`, `RoutedLlmClient`) is
verified by structure check and review only until CI builds it.

### 7.5 P2 — still open

A deterministic task-quality suite (goal success / wrong-recipient / verification
accuracy with confidence intervals) is deliberately **not** in this change. It needs a
recorded task corpus and a scoring harness, and inventing numbers before that exists
would be the same dishonesty the rest of this pack is trying to remove.

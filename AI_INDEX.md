# ReturnGift AI Index

This is the repo map for coding agents. Keep canonical information in existing files; do not create new root docs when one of these files already owns the topic.

## Canonical Root Docs

| File | Owns |
|------|------|
| `README.md` | Project overview and architecture direction |
| `CLAUDE.md` | Agent/project working rules |
| `QA_CHECKLIST.md` | QA methodology, release gate, test cases |
| `RELEASING.md` | Release signing, tag workflow, APK publishing |
| `BACKLOG.md` | Prioritized bugs, features, QA gaps |
| `AI_INDEX.md` | This repo map |

## Directory Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/com/returngift/agent/` | Android app source |
| `app/src/main/assets/playbooks/` | Built-in playbooks used by the agent harness |
| `scripts/` | QA and automation scripts |
| `.github/workflows/` | CI and signed release workflow |
| `docs/specs/` | Feature/subsystem design specs (e.g. `tier1-intent-matching.md`) |
| `fixtures/` | Golden corpora for deterministic layers (e.g. `tier1_golden_utterances.jsonl`) |

## Key Implementation Files (W1–W9 + U-pack additions)

| File | Purpose |
|------|---------|
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppCatalog.kt` | App address registry — built at setup, persists JSON + vault markdown |
| `app/src/main/java/com/returngift/agent/agent/exec/TargetSpecGate.kt` | Pure Kotlin gate detecting enumeration-without-names (e.g., "open 5 apps") |
| `app/src/main/java/com/returngift/agent/agent/exec/DeterministicUiExecutor.kt` | Bounded executor with WAIT_FOR_TARGET polling + waitForMs per step |
| `app/src/main/java/com/returngift/agent/agent/exec/StructuredRoutineRegistry.kt` | Registry of deterministic routines (data-driven, one entry per capability) |
| `app/src/main/java/com/returngift/agent/agent/exec/routines/SocialPostRoutine.kt` | "Post <text> on <social app>" routine — app labels live in `profiles` rows, never in code |
| `app/src/main/java/com/returngift/agent/agent/exec/micro/MicroStepCatalog.kt` | 73 mid-task micro-step rules (type → Enter/Send, tap → confirm, permission → Allow…) |
| `app/src/main/java/com/returngift/agent/agent/exec/micro/MicroStepPlanner.kt` | Deterministic planner: context → mandatory next micro-step(s) + guidance text |
| `app/src/main/java/com/returngift/agent/agent/exec/micro/MicroStepTracker.kt` | Per-task state machine; vetoes premature `finish` while an action is outstanding |
| `app/src/main/java/com/returngift/agent/agent/exec/micro/MicroStepBridge.kt` | Tool call (name/params/foreground pkg) → micro-step context |
| `app/src/main/java/com/returngift/agent/agent/permission/PermissionRequest.kt` | Pure permission-request model (app access vs system capability, choices, decisions) |
| `app/src/main/java/com/returngift/agent/agent/permission/PermissionPromptBroker.kt` | Blocking bridge: opens the permission card, parks the agent thread, returns the decision |
| `app/src/main/java/com/returngift/agent/agent/permission/PermissionPromptLauncher.kt` | Foreground launch of the permission card from any context |
| `app/src/main/java/com/returngift/agent/ui/permission/PermissionPromptActivity.kt` | The APK's permission card (icon, reason, Allow once / Always allow / Cancel, Open settings) |
| `app/src/main/java/com/returngift/agent/service/PermissionPromptNotifier.kt` | Heads-up mirror with the same choices when the app is in the background |
| `app/src/main/java/com/returngift/agent/service/PermissionAnswerReceiver.kt` | Notification answer → broker |
| `app/src/main/java/com/returngift/agent/agent/AppAllowListStore.kt` | Per-app policy store (DB v2): mode = always / ask / blocked, with migration |
| `app/src/main/java/com/returngift/agent/agent/permission/AppAccessMode.kt` | Pure tri-state enum (Always allow / Ask / Blocked) |
| `app/src/main/java/com/returngift/agent/agent/permission/AppAccessPolicy.kt` | Pure rules: when to ask, what to persist for each card answer |
| `app/src/main/java/com/returngift/agent/agent/permission/AppAccessGate.kt` | Single runtime decision point used by the agent loop and Tier-1 tools |
| `app/src/main/java/com/returngift/agent/agent/prefs/AgentPreferences.kt` | User toggles: ask before new app, remember permanently, open card, notify, enforce micro-steps |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppUsageGuide.kt` | Per-app recipes + surfaces (Chrome, LinkedIn, YouTube, WhatsApp, …) for prompt + UI |
| `app/src/main/java/com/returngift/agent/agent/knowledge/InstalledApps.kt` | Cached launchable-app list (PackageManager) |
| `app/src/main/java/com/returngift/agent/ui/settings/AppAllowListActivity.kt` | Settings → App permissions: toggles + per-app Always/Ask/Blocked rows with hints |
| `app/src/main/java/com/returngift/agent/agent/plan/PlanModel.kt` | Plan data model (`TaskIntent`, `PlanStep`, `PlanQuestion`, `TaskBlueprint`, renderer) |
| `app/src/main/java/com/returngift/agent/agent/plan/IntentParser.kt` | Deterministic NL → intent (follow/message/post/search/play/open/toggle) + entity typing |
| `app/src/main/java/com/returngift/agent/agent/plan/PlanComposer.kt` | Intent + installed apps → readable, executable steps (capability-based, never per-app) |
| `app/src/main/java/com/returngift/agent/agent/plan/PlanFirst.kt` | Mode/presentation policy: AUTO, PREVIEW ("show me the plan"), SILENT ("just do it") |
| `app/src/main/java/com/returngift/agent/agent/plan/PlanClarifier.kt` | Folds answers into the request and re-plans (no stale assumptions) |
| `app/src/main/java/com/returngift/agent/agent/plan/PlanContextFactory.kt` | Android glue: installed-app census + guide registry → `PlanContext` |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpClient.kt` | MCP JSON-RPC client: initialize, tools/list (paged), tools/call, resources/read, session recovery |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpJson.kt` | Dependency-free JSON for the protocol layer (keeps it JVM-testable) |
| `app/src/main/java/com/returngift/agent/agent/mcp/HttpMcpTransport.kt` | Streamable-HTTP transport on `HttpURLConnection` (no new dependency) |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpRuntime.kt` | Connects servers, keeps the bounded tool index, routes calls, fails open |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpToolIndex.kt` | Ranks MCP tools per task and caps how many reach the prompt |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpServerStore.kt` | Persisted connection rows (KV seam, validated, max 16) |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpKnowledgeBridge.kt` | MCP resource → `AppKnowledgeSource` (knowledge without a release) |
| `app/src/main/java/com/returngift/agent/agent/mcp/McpToolRegistrar.kt` | Publishes MCP tools into `ToolRegistry` (sync cache + async refresh) |
| `app/src/main/java/com/returngift/agent/agent/routing/ProviderCatalog.kt` | Model catalog for routing: prices from `CloudProvider` + quality/latency/capabilities |
| `app/src/main/java/com/returngift/agent/agent/routing/TokenLedger.kt` | Token/cost ledger with 5 h + 7 d windows, budgets and cooldowns |
| `app/src/main/java/com/returngift/agent/agent/routing/ProviderRouter.kt` | Strategy scoring, filters, ordered failover chain, failure classification |
| `app/src/main/java/com/returngift/agent/agent/llm/RoutedLlmClient.kt` | Executes the routing chain with real provider clients and records egress |
| `docs/MCP_AND_MODEL_ROUTING.md` | Design + repo study (mcp-extensions, OpenWA, hive, OmniRoute/Antigravity) + size measurements |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppGuide.kt` | App knowledge ROW model + `GuideSource` (builtin/connector/learned) + precedence |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppGuideCodec.kt` | Dependency-free wire format for rows (store + connectors) |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppGuideStore.kt` | Runtime rows learned on device / cached from connectors (KV, max 64) |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppKnowledgeSource.kt` | Knowledge-source seam (builtin / store / MCP connector) + `AppKnowledge` merge |
| `app/src/main/java/com/returngift/agent/agent/knowledge/AppGuideLearner.kt` | Distils successful task steps into learned guides (evidence-gated) |
| `scripts/check-app-agnostic.py` | CI guard: fails on any file dedicated to a single app |
| `app/src/main/java/com/returngift/agent/agent/flow/AppDeepLink.kt` | Deep-link registry (documented vs best effort), URI encoding, search-term extraction |
| `app/src/main/java/com/returngift/agent/agent/flow/SessionBlocker.kt` | Detects login walls, captcha, account chooser, consent, throttling, network errors + user messages |
| `app/src/main/java/com/returngift/agent/agent/flow/FlowRetry.kt` | Retry budgets with capped exponential backoff (search/navigation/network/save) |
| `app/src/main/java/com/returngift/agent/agent/flow/AppSessionMemory.kt` | Per-app consent (30 d) and signed-in (7 d) facts; cleared by the Settings row Reset |
| `app/src/main/java/com/returngift/agent/agent/TokenMonitor.kt` | Token/cost display counters — NO ceiling (the 200K CRITICAL abort was removed) |
| `app/src/main/java/com/returngift/agent/agent/PipelineRouter.kt` | Tier-1 router with isEscalation param to skip to Tier-3 |
| `app/src/main/java/com/returngift/agent/agent/Tier1Telemetry.kt` | Escalation tracking for routing quality |
| `app/src/main/java/com/returngift/agent/agent/dryrun/DryRunRunner.kt` | Preview mode with keep_preview_mode + context-aware Toast |
| `app/src/main/java/com/returngift/agent/ui/chat/ChatScreen.kt` | Chat UI with new TopAppBar, ModelSheet, mode chip, preview banner |
| `app/src/main/java/com/returngift/agent/ui/chat/TaskFlowController.kt` | Task flow with preview auto-reset in cleanupAfterTask |

## Removed Files

| File | Reason |
|------|--------|
| `app/src/main/java/com/returngift/agent/agent/routing/AdaptiveRouter.kt` | Dead code — no-op recordResult, live routing is PipelineRouter |
| `app/src/main/java/com/returngift/agent/agent/pipeline/IntegratedAgentPipeline.kt` | Dead code — unused |

## Direction Rules

→ See `README.md` for ReturnGift's project plan and architecture direction.

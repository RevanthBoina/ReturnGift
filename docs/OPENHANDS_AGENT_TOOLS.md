# OpenHands Agent Tools Reference

This document describes all the tools available to the OpenHands agent in this environment for working with the ReturnGift repository. It covers GitHub Actions integration, git operations, task tracking, and development workflows.

## Table of Contents
1. [GitHub Actions / Build Logs](#github-actions--build-logs)
2. [Git Operations](#git-operations)
3. [Task Tracking & Planning](#task-tracking--planning)
4. [File Operations](#file-operations)
5. [Terminal / Shell Commands](#terminal--shell-commands)
6. [GitHub API / PR Management](#github-api--pr-management)
7. [Web Research / Browser Tools](#web-research--browser-tools)
8. [Code Navigation & Search](#code-navigation--search)
9. [Workflow Patterns](#workflow-patterns)

---

## GitHub Actions / Build Logs

### View Workflow Runs
```bash
# List recent workflow runs for a specific workflow
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs?workflow_id=release.yml&per_page=5" | \
  jq '.workflow_runs[] | {id: .id, name: .display_title, status: .status, conclusion: .conclusion, head_branch: .head_branch, head_sha: .head_sha, created_at: .created_at}'

# Check specific run status
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs/<RUN_ID>" | \
  jq '{id: .id, status: .status, conclusion: .conclusion, head_branch: .head_branch}'
```

### Get Job Details
```bash
# List jobs for a workflow run
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs/<RUN_ID>/jobs" | \
  jq '.jobs[] | {name: .name, status: .status, conclusion: .conclusion}'

# Get build logs (gzipped)
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/jobs/<JOB_ID>/logs" | \
  gunzip -c 2>/dev/null | grep -A 30 "compileReleaseKotlin\|error:"
```

### Key Workflows
| Workflow File | Trigger | Purpose |
|--------------|---------|---------|
| `release.yml` | Push tags `v*` | Build signed release APK |
| `auto_build_and_test.yml` | PR/push to main | Unit tests, lint, debug APK |
| `emulator-matrix.yml` | PR/push | Android emulator tests |
| `build.yml` | Push to main | Debug APK build |

---

## Git Operations

### Basic Commands
```bash
# Check status
git status

# View commit history
git log --oneline -10

# Show diff between branches/tags
git diff v3.0.13..fix/compilation-errors --stat
git diff v3.0.13..fix/compilation-errors -- path/to/file.kt

# Check remote tags
git ls-remote --tags origin | grep v3.0
```

### Branch Management
```bash
# Create/switch branch
git checkout -B fix/compilation-errors

# Reset branch to match tag
git reset --hard v3.0.13 && git checkout -B fix/compilation-errors

# Push branch (force if needed)
git push origin fix/compilation-errors --force
```

### Tag Management
```bash
# List local tags
git tag -l "v3*"

# Create tag
git tag v3.0.14 <commit-sha>

# Delete local tag
git tag -d v3.0.14

# Push tag
git push origin v3.0.14

# Create tag via GitHub API (when git push hangs)
curl -X POST -H "Authorization: token $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/RevanthBoina/ReturnGift/git/refs \
  -d '{"ref":"refs/tags/v3.0.14","sha":"<commit-sha>"}'
```

### Authentication for Git Push
When `git push` hangs on password prompt, use explicit token:
```bash
git push https://${GITHUB_TOKEN}@github.com/RevanthBoina/ReturnGift.git main
```

---

## Task Tracking & Planning

### task_tracker Tool
```bash
# View current task list
task_tracker --command view

# Create/update task list
task_tracker --command plan --task_list '[{"title": "Fix compilation errors", "status": "in_progress", "notes": "ChatScreen.kt DropdownMenu"}, {"title": "Trigger release build", "status": "todo", "notes": "Push v3.0.14 tag"}]'
```

### Task Status Values
- `todo` - Not yet initiated
- `in_progress` - Currently active (limit to ONE at a time)
- `done` - Successfully completed

---

## File Operations

### file_editor Tool
```bash
# View file
file_editor --command view --path /absolute/path/to/file.kt

# View specific lines
file_editor --command view --path /absolute/path/to/file.kt --view_range [1, 50]

# Create new file
file_editor --command create --path /absolute/path/to/new_file.md --file_text "content"

# Replace string (exact match required)
file_editor --command str_replace --path /absolute/path/to/file.kt --old_str "old code" --new_str "new code"

# Insert after line
file_editor --command insert --path /absolute/path/to/file.kt --insert_line 10 --new_str "new line"

# Undo last edit
file_editor --command undo_edit --path /absolute/path/to/file.kt
```

---

## Terminal / Shell Commands

### terminal Tool
```bash
# Execute command
terminal --command "cd /workspace/project/a0b7cb4b21934f7786de69e9a7930628/ReturnGift && ./gradlew assembleDebug" --timeout 300

# With summary for tracking
terminal --command "bash scripts/ci-preflight.sh" --summary "Run CI preflight checks"

# Long-running commands (background)
terminal --command "sleep 180 && curl ..." --timeout 300
```

### Important Notes
- **No Android SDK/JDK** in sandbox — Gradle builds cannot run locally
- Use `reset` to clear stuck terminal
- Use `C-c` to interrupt hanging commands
- Working directory persists across commands

---

## GitHub API / PR Management

### Create PR (gh CLI)
```bash
gh pr create --base main --head fix/compilation-errors \
  --title "fix: resolve compilation errors for release build" \
  --body "Description of changes"
```

### Merge PR
```bash
gh pr merge 86 --merge --delete-branch
```

### Check PR Status
```bash
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/pulls/86" | \
  jq '{url: .html_url, state: .state, head: .head.ref, base: .base.ref}'
```

### Get Release Assets
```bash
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/releases/tags/v3.0.14" | \
  jq '{tag_name: .tag_name, published_at: .published_at, assets: [.assets[] | {name: .name, size: .size, browser_download_url: .browser_download_url}]}'
```

---

## Web Research / Browser Tools

### browser_navigate
```bash
browser_navigate --url "https://github.com/RevanthBoina/ReturnGift/actions"
```

### browser_get_state
```bash
browser_get_state --include_screenshot false
```

### browser_click / browser_type
```bash
# Click element by index from get_state
browser_click --index 5

# Type in input field
browser_type --index 3 --text "search query"
```

### tavily_search (Web Research)
```bash
tavily_tavily_search --query "Kotlin @file:OptIn annotation placement" --max_results 5
```

---

## Code Navigation & Search

### grep / find
```bash
# Search for pattern in Kotlin files
grep -rn "@OptIn(" app/src/main/java/ --include="*.kt"

# Find files with pattern
find app/src -name "*.kt" -exec grep -l "DropdownMenu" {} \;

# Check for problematic patterns
grep -rn "ic_menu_compose" app/src/main/
grep -rn "\.dp /" app/src/main/java/ --include="*.kt"
grep -rn "catch.*InterruptedException" app/src/main/java/ --include="*.kt"
```

### Git Show (View file at specific tag/commit)
```bash
# View file at tag
git show v3.0.13:app/src/main/java/com/returngift/agent/ui/chat/ComposeChatActivity.kt

# View file at commit
git show <commit-sha>:path/to/file.kt

# Restore file from tag
git show v3.0.13:path/to/file.kt > path/to/file.kt
```

---

## Workflow Patterns

### Pattern 1: Fix Compilation Error → Release Build
```bash
# 1. Identify error from build logs
# 2. Fix code locally
file_editor --command str_replace --path ... 

# 3. Run preflight check (MUST PASS)
bash scripts/ci-preflight.sh

# 4. Commit and push to branch
git add . && git commit -m "fix: ..." && git push origin fix/branch

# 5. Create PR
gh pr create --base main --head fix/branch --title "..." --body "..."

# 6. Merge PR
gh pr merge <PR_NUM> --merge --delete-branch

# 7. Create NEW tag from main (increment version)
git tag v3.0.15 <main-sha>

# 8. Push tag to trigger release build
git push https://${GITHUB_TOKEN}@github.com/RevanthBoina/ReturnGift.git v3.0.15

# 9. Monitor build
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs?workflow_id=release.yml&per_page=1" | \
  jq '.workflow_runs[0] | {id: .id, status: .status, conclusion: .conclusion}'
```

### Pattern 2: Debug Failed Release Build
```bash
# Get latest failed run
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs?workflow_id=release.yml&per_page=3" | \
  jq '.workflow_runs[] | select(.conclusion=="failure") | {id: .id, head_branch: .head_branch}'

# Get job ID
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/runs/<RUN_ID>/jobs" | \
  jq '.jobs[] | select(.name=="Build & Release") | .id'

# Get logs
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/actions/jobs/<JOB_ID>/logs" | \
  gunzip -c 2>/dev/null | grep -B 5 -A 30 "error:\|FAILED"
```

### Pattern 3: Verify Release Artifacts
```bash
# Check release exists
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  "https://api.github.com/repos/RevanthBoina/ReturnGift/releases/tags/v3.0.14"

# Verify APK downloadable
curl -sI "https://github.com/RevanthBoina/ReturnGift/releases/download/v3.0.14/ReturnGift-release.apk" | head -5
```

### Pattern 4: Documentation Update
```bash
# Create docs
cat > docs/RELEASE_BUILD_DIAGNOSTICS.md << 'EOF'
# Content here
EOF

# Update AGENTS.md
cat > AGENTS.md << 'EOF'
# Content here
EOF

# Commit and push
git add AGENTS.md docs/RELEASE_BUILD_DIAGNOSTICS.md
git commit -m "docs: update release build diagnostics"
git push https://${GITHUB_TOKEN}@github.com/RevanthBoina/ReturnGift.git main
```

---

## Critical Rules (from AGENTS.md)

### CI Preflight Checks (scripts/ci-preflight.sh)
**Runs FIRST in CI — catches these errors:**
1. `android.R.drawable.ic_menu_compose` — doesn't exist
2. `return` in default parameter values — illegal in Kotlin
3. Missing imports for `ToolResult`/`BaseTool`/`ToolParameter` in Java sub-packages
4. Kotlin `Result` value class called from Java (mangled name)
5. Brace/paren/bracket balance in Kotlin files
6. Unreachable `catch (InterruptedException)` blocks

### Kotlin Specific Rules
- `@file:OptIn` MUST precede `package` declaration
- `Dp / Int` returns `Float` — use `(16.dp / 2).dp`
- `DropdownMenu` content lambda must contain ALL items
- Bare `this` in coroutine lambda = `CoroutineScope`, not Activity

### Java Specific Rules
- Tools in sub-packages (e.g., `tool.impl.tv`) must explicitly import `ToolResult`, `BaseTool`, `ToolParameter`
- Never call Kotlin `Result` returning functions from Java — create `*FromJava` wrappers

### Tag/Release Rules
- Release workflow ONLY triggers on `push: tags: 'v*'`
- Each tag must represent unique commit SHA
- Don't force-push existing tags — create incremented versions
- Tag from main branch: `git tag vX.Y.Z <main-sha>`

---

## Quick Reference Commands

| Task | Command |
|------|---------|
| Run preflight | `bash scripts/ci-preflight.sh` |
| Check @file:OptIn | `grep -rn "@OptIn(" app/src/main/java/ --include="*.kt" \| grep -v "@file:"` |
| Check Dp division | `grep -rn "\.dp /" app/src/main/java/ --include="*.kt"` |
| Check ic_menu_compose | `grep -rn "ic_menu_compose" app/src/main/` |
| Check unreachable catch | `grep -rn "catch.*InterruptedException" app/src/main/java/ --include="*.kt"` |
| View latest release build | `gh run list --workflow=release.yml --limit=3` |
| Watch build | `gh run watch <RUN_ID>` |
| Download APK | `gh release download v3.0.14 -p ReturnGift-release.apk` |

---

## Environment Variables
- `$GITHUB_TOKEN` - Auto-injected for GitHub API calls
- `$OPENHANDS_API_KEY` - OpenHands Cloud API key

---

## Related Documentation
- `AGENTS.md` - Repository memory and rules
- `CLAUDE.md` - Project rules and architecture
- `QA_CHECKLIST.md` - E2E test cases
- `RELEASING.md` - Release process
- `docs/RELEASE_BUILD_DIAGNOSTICS.md` - Detailed pitfalls and fixes

---

*Last Updated: 2026-09-06 (v3.0.14 release)*
*For OpenHands agents working on ReturnGift repository*
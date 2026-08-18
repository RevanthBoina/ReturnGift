#!/usr/bin/env bash
# Copyright 2026 ReturnGift Project. All rights reserved.
# Licensed under the Apache License, Version 2.0.
#
# Enforce a PR-based workflow on the `main` branch:
#   - require at least one approving review before merge
#   - require the CI status checks (Auto Build & Test / Build Debug APK) to pass
#
# Uses the GitHub CLI (gh) REST API to create a repository ruleset.
# Run once per repo. Requires `gh auth login` with a token that has administration:write.
#
# Usage:
#   ./scripts/setup-repo-rules.sh owner/repo
#
# If the repo arg is omitted, it defaults to RevanthBoina/ReturnGift.
set -euo pipefail

REPO="${1:-RevanthBoina/ReturnGift}"

if ! command -v gh >/dev/null 2>&1; then
  echo "::error::GitHub CLI (gh) is required. Install from https://cli.github.com/"
  exit 1
fi

echo "Creating PR-review + CI status-check ruleset on 'main' for $REPO..."

# Required status checks — must match the workflow job names in
# .github/workflows/auto_build_and_test.yml and build.yml.
CHECKS='[
  {"target":"commit","context":"Build, Test & Lint"},
  {"target":"commit","context":"Build Debug APK"}
]'

# Create the ruleset enforcing: PRs only, require review, require the checks above.
gh api --method POST -H "Accept: application/vnd.github+json" \
  "/repos/${REPO}/rulesets" \
  -f name="Require PR review + CI on main" \
  -f target="branch" \
  -f enforcement="active" \
  -F "conditions[ref_name][]=main" \
  -f "rules[0][type]=pull_request" \
  -f "rules[0][parameters][required_approving_review_count]=1" \
  -f "rules[0][parameters][dismiss_stale_reviews]=true" \
  -f "rules[0][parameters][require_code_owner_review]=false" \
  -f "rules[1][type]=required_status_checks" \
  --input - <<EOF
{
  "name": "Require PR review + CI on main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["main"] } },
  "rules": [
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews": true,
        "require_code_owner_review": false,
        "required_review_thread_resolution": false
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "required_status_checks": [
          {"context": "Build, Test & Lint", "integration_id": null},
          {"context": "Build Debug APK", "integration_id": null}
        ],
        "strict_required_status_checks_policy": false
      }
    }
  ]
}
EOF

echo "✅ Ruleset created. 'main' now requires a PR with 1 approving review and passing CI."
echo "   Verify in: https://github.com/${REPO}/settings/rules"

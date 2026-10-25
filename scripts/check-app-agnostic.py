#!/usr/bin/env python3
"""Fail the build when a source/test file is dedicated to a single app.

Repo rule (see AGENTS.md → "No per-app files"): app behaviour must be generic and
driven by data — an app may appear as a *row* in a registry, a skill/playbook
asset, or a connector, but never as its own Kotlin/Java file. Per-app files rot,
double the maintenance cost and make the next app a code change instead of data.

Allowed on purpose:
  * `app/src/main/assets/**`         playbooks (human-readable flows, data)
  * `skill_library/**`               YAML skills (data, per-capability by design)
  * channel/integration handlers     `channel/<service>/...` — these are external
                                     service connectors (Telegram, Discord, ...),
                                     the same role an MCP connector plays
  * registry/table files             a file may MENTION apps, it just must not be
                                     named after one or contain nothing else

Exit code 0 = clean, 1 = violation (printed with the suggested fix).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CODE_ROOTS = [ROOT / "app/src/main/java", ROOT / "app/src/test/java", ROOT / "app/src/androidTest/java"]

# App slugs that must not become file names. Keep this list app-agnostic in spirit:
# it is a guard, not an inventory of supported apps.
APP_SLUGS = [
    "linkedin", "whatsapp", "youtube", "instagram", "facebook", "twitter",
    "telegram", "spotify", "gmail", "chrome", "snapchat", "reddit", "tiktok",
    "pinterest", "discord", "netflix", "amazon", "uber", "swiggy", "zomato",
    "outlook", "maps", "paytm", "phonepe", "gpay",
]

# Paths that are connectors to an external service rather than app automation.
ALLOWED_PATH_PARTS = [
    "/channel/",       # per-service channel connectors (bot APIs)
    "/connectors/",    # MCP connector implementations
]

FILE_SUFFIXES = {".kt", ".java"}

CAMEL_SPLIT = re.compile(r"(?<!^)(?=[A-Z])")


def slugified(name: str) -> str:
    """MyWhatsAppFlowTest.kt -> mywhatsappflowtest, whatsapp_flow.kt -> whatsappflow."""
    return re.sub(r"[^a-z0-9]", "", name.lower())


def violations() -> list[tuple[Path, str, str]]:
    found: list[tuple[Path, str, str]] = []
    for code_root in CODE_ROOTS:
        if not code_root.exists():
            continue
        for path in code_root.rglob("*"):
            if path.suffix not in FILE_SUFFIXES:
                continue
            posix = "/" + path.as_posix()
            if any(part in posix for part in ALLOWED_PATH_PARTS):
                continue
            flat = slugified(path.stem)
            for slug in APP_SLUGS:
                if slug in flat:
                    found.append(
                        (
                            path,
                            slug,
                            "rename it after the capability (e.g. SocialPostRoutine, "
                            "AppFlowRecoveryTest) and keep the app as a row in "
                            "AppUsageGuide / AppDeepLink / SocialPostRoutine.profiles, "
                            "or deliver it through AppKnowledge (connector / learned).",
                        )
                    )
                    break
    return found


def main() -> int:
    bad = violations()
    if not bad:
        print("\033[32mOK no-per-app-files (code and tests are app-agnostic)\033[0m")
        return 0
    print("\033[31mFAIL no-per-app-files — do not dedicate a file to one app:\033[0m")
    for path, slug, fix in bad:
        rel = path.relative_to(ROOT)
        print(f"  x {rel} (single app: {slug})")
        print(f"    → {fix}")
    print("\nSee AGENTS.md → \"No per-app files\" and the data registries in agent/knowledge/.")
    return 1


if __name__ == "__main__":
    sys.exit(main())

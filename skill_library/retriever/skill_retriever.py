"""
skill_library/retriever/skill_retriever.py

Retrieves the best-matching skill for a given task string.
Strategy: priority-weighted keyword overlap + anti-trigger filtering.
No external ML dependencies — runs on-device with the 270M model constraint.
"""

from __future__ import annotations

import re
import yaml
from pathlib import Path
from typing import Any

_LIBRARY_ROOT = Path(__file__).parent.parent


def _load_index() -> list[dict]:
    index_path = _LIBRARY_ROOT / "index.yaml"
    with index_path.open() as f:
        return yaml.safe_load(f)["skills"]


def _load_skill(file: str) -> dict:
    with (_LIBRARY_ROOT / file).open() as f:
        return yaml.safe_load(f)


def _tokenize(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9]+", text.lower()))


class SkillRetriever:
    """
    Loads all skills from the library index and retrieves the best match
    for a task string using trigger overlap + priority scoring.
    """

    def __init__(self):
        index = _load_index()
        self._skills: list[dict] = []
        for entry in index:
            if entry.get("status", "stable") == "draft":
                continue  # skip draft skills
            skill = _load_skill(entry["file"])
            if skill:
                self._skills.append(skill)

    def retrieve(self, task: str) -> dict | None:
        """Return the highest-scoring skill for *task*, or None."""
        task_tokens = _tokenize(task)
        best_skill: dict | None = None
        best_score = -1.0

        for skill in self._skills:
            routing = skill.get("routing", {})
            priority = routing.get("priority", 10)
            triggers: list[str] = routing.get("triggers", [])
            anti_triggers: list[str] = routing.get("anti_triggers", [])

            # Anti-trigger veto: if any anti-trigger pattern matches, skip
            if any(_tokenize(at.split("->")[0]).issubset(task_tokens) for at in anti_triggers):
                continue

            # Score = fraction of trigger tokens present in task, weighted by priority
            trigger_scores = []
            for trigger in triggers:
                t_tokens = _tokenize(re.sub(r"\{[^}]+\}", "", trigger))  # strip slot placeholders
                if not t_tokens:
                    continue
                overlap = len(t_tokens & task_tokens) / len(t_tokens)
                trigger_scores.append(overlap)

            if not trigger_scores:
                continue

            max_overlap = max(trigger_scores)
            if max_overlap == 0:
                continue

            score = max_overlap * (priority / 100.0)
            if score > best_score:
                best_score = score
                best_skill = skill

        return best_skill

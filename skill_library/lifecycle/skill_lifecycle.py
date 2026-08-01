"""
skill_library/lifecycle/skill_lifecycle.py

Implements the complete skill lifecycle:
  Agent Task → Retrieve Skill → Execute Skill → Verify Skill
             → Skill Refiner/Optimizer → Skill Library Update

Failed executions or low-confidence results automatically flow through
verification and refinement before any skill updates are persisted.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

logger = logging.getLogger(__name__)


class LifecycleOutcome(str, Enum):
    SUCCESS = "success"
    REFINED = "refined"
    REJECTED = "rejected"
    ESCALATED = "escalated"


@dataclass
class ExecutionContext:
    task: str
    skill_id: str
    slots: dict[str, Any] = field(default_factory=dict)
    execution_log: list[dict] = field(default_factory=list)
    user_feedback: str | None = None


@dataclass
class LifecycleResult:
    outcome: LifecycleOutcome
    skill_id: str
    message: str
    refined_skill: dict | None = None


class SkillLifecycle:
    """
    Orchestrates the full skill lifecycle for a single agent task.
    Wires together retriever → executor → evaluator → optimizer → library.
    """

    CONFIDENCE_THRESHOLD = 0.75

    def __init__(self, retriever, executor, evaluator, optimizer, library):
        self._retriever = retriever
        self._executor = executor
        self._evaluator = evaluator
        self._optimizer = optimizer
        self._library = library

    def run(self, task: str, slots: dict[str, Any] | None = None) -> LifecycleResult:
        """Entry point: run the full lifecycle for a task."""
        slots = slots or {}

        # 1. Retrieve
        skill = self._retriever.retrieve(task)
        if skill is None:
            logger.warning("No skill found for task: %s", task)
            return LifecycleResult(LifecycleOutcome.ESCALATED, "", "No matching skill found.")

        ctx = ExecutionContext(task=task, skill_id=skill["skill_id"], slots=slots)
        logger.info("Retrieved skill: %s", ctx.skill_id)

        # 2. Execute
        exec_result = self._executor.execute(skill, ctx)
        ctx.execution_log = exec_result.get("log", [])

        # 3. Verify
        eval_result = self._evaluator.evaluate(skill, ctx, exec_result)
        confidence = eval_result.get("confidence", 0.0)
        passed = eval_result.get("passed", False)

        if passed and confidence >= self.CONFIDENCE_THRESHOLD:
            logger.info("Skill %s verified (confidence=%.2f)", ctx.skill_id, confidence)
            return LifecycleResult(LifecycleOutcome.SUCCESS, ctx.skill_id, exec_result.get("message", ""))

        # 4. Refine — only on failure or low confidence
        logger.warning("Skill %s failed verification (confidence=%.2f). Refining...", ctx.skill_id, confidence)
        refined = self._optimizer.refine(skill, ctx, exec_result, eval_result)

        if refined is None:
            return LifecycleResult(LifecycleOutcome.ESCALATED, ctx.skill_id, "Refinement produced no candidate.")

        # 5. Re-evaluate refined skill before persisting
        re_eval = self._evaluator.evaluate(refined, ctx, exec_result)
        if not re_eval.get("passed", False):
            logger.error("Refined skill %s still failed evaluation. Rejecting.", ctx.skill_id)
            return LifecycleResult(LifecycleOutcome.REJECTED, ctx.skill_id,
                                   "Refined skill failed safety/quality checks.", refined_skill=refined)

        # 6. Update library only after verified refinement
        self._library.update(refined)
        logger.info("Skill library updated for: %s", ctx.skill_id)
        return LifecycleResult(LifecycleOutcome.REFINED, ctx.skill_id,
                               "Skill refined and library updated.", refined_skill=refined)

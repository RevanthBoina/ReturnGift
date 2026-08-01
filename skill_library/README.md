# skill_library

Production-ready skill definitions and the Python lifecycle pipeline.

## Structure

```
skill_library/
├── skills/              # 19 YAML skill definitions (schema v2.1)
├── retriever/           # skill_retriever.py  — keyword+priority scoring
├── evaluator/           # skill_evaluator.py  — postcondition assertion checker
├── optimizer/           # skill_optimizer.py  — reliability demotion + selector refinement
├── lifecycle/           # skill_lifecycle.py  — orchestrates retrieve→execute→verify→refine
└── index.yaml           # Master skill index (auto-updated by SkillLibraryLoader)
```

## Lifecycle Flow

```
User Task
   │
   ▼
SkillRetriever.retrieve(task)
   │  keyword overlap × priority score
   ▼
SkillExecutor.execute(skill, ctx)
   │  deterministic route → UI fallback
   ▼
SkillEvaluator.evaluate(skill, ctx, result)
   │  postcondition assertions → confidence score
   ├─ pass (≥0.75) ──► SUCCESS
   └─ fail ──────────► SkillOptimizer.refine(skill, ctx, result)
                           │  demote failed route, boost alternatives
                           ▼
                       re-evaluate → persist if passes
```

## Adding a New Skill

1. Create `skills/<skill_id>.yaml` following schema v2.1 (see `docs/specs/skill-spec.md`)
2. Add an entry to `index.yaml`
3. Create a matching playbook in `app/src/main/assets/playbooks/<skill-id>.md`

# Writing User Story FR Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement explicit PRD and FR selection for `writing-user-story`, compute `main` versus `patch` deterministically, and expand the skill eval flow to benchmark the revised behavior against the current skill.

**Architecture:** Add one planner script that reads the parent PRD and existing user stories and returns machine-readable selection and role data. Keep `create_user_story.py` and `validate_doc.py` as single-story utilities, move the interactive selection rules into `SKILL.md`, and extend the eval fixtures so the skill-creator workflow can compare the revised skill against the current version.

**Tech Stack:** Markdown skill instructions, Python 3 scripts, JSON eval fixtures, `rg`, `python3`, `git`, and the skill-creator benchmark/viewer scripts.

---

### Task 0: Snapshot The Old Skill Baseline

**Files:**
- Create: `skills/toad-copilot/writing-user-story-workspace/skill-snapshot/`

- [ ] **Step 1: Snapshot the current skill before any edits**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
rm -rf skills/toad-copilot/writing-user-story-workspace/skill-snapshot
mkdir -p skills/toad-copilot/writing-user-story-workspace
cp -R skills/toad-copilot/writing-user-story skills/toad-copilot/writing-user-story-workspace/skill-snapshot
```

Expected: PASS with a copy of the pre-change skill saved under `writing-user-story-workspace/skill-snapshot`.

### Task 1: Add Planner Script And Duplicate Fixture

**Files:**
- Create: `skills/toad-copilot/writing-user-story/scripts/plan_user_stories.py`
- Create: `skills/toad-copilot/writing-user-story/evals/files/docs/user-stories/us-00001-existing-guest-checkout-without-account.md`

- [ ] **Step 1: Write the failing planner check**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
python3 skills/toad-copilot/writing-user-story/scripts/plan_user_stories.py --parent prd-00020-checkout-redesign
```

Expected: FAIL with `No such file or directory` because `plan_user_stories.py` does not exist yet.

- [ ] **Step 2: Write the planner script**

Create `skills/toad-copilot/writing-user-story/scripts/plan_user_stories.py` with this content:

```python
#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


PRD_ID_RE = re.compile(r"^prd-\d{5}-[a-z0-9-]+$")
FR_ID_RE = re.compile(r"^FR-\d{2,}$")
FR_LINE_RE = re.compile(r"^\s*-\s*\[[ xX]\]\s*(FR-\d{2,}):", flags=re.MULTILINE)
PROJECT_ROOT_MARKERS = (
    ".git",
    "AGENTS.md",
    "package.json",
    "pyproject.toml",
    "go.mod",
    "Cargo.toml",
)


def find_project_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if any((candidate / marker).exists() for marker in PROJECT_ROOT_MARKERS):
            return candidate
    return current


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plan a writing-user-story run without creating any files."
    )
    parser.add_argument(
        "--parent",
        required=True,
        help="Parent PRD id, for example prd-00020-checkout-redesign",
    )
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument(
        "--all",
        action="store_true",
        dest="select_all",
        help="Select all FR ids from the parent PRD",
    )
    selection.add_argument(
        "--select",
        help="Comma-separated FR ids, for example FR-01,FR-03",
    )
    return parser.parse_args()


def read_front_matter(doc_path: Path) -> dict[str, str]:
    content = doc_path.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---(?:\n|$)", content, flags=re.DOTALL)
    if not match:
        return {}

    front_matter: dict[str, str] = {}
    for raw_line in match.group(1).splitlines():
        line = raw_line.strip()
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        front_matter[key.strip()] = value.strip()
    return front_matter


def extract_function_requirement_ids(prd_path: Path) -> list[str]:
    content = prd_path.read_text(encoding="utf-8")
    function_requirement_ids = FR_LINE_RE.findall(content)
    if not function_requirement_ids:
        raise SystemExit(f"no functional requirements found in {prd_path}")
    return function_requirement_ids


def parse_selection(raw_selection: str) -> list[str]:
    selected_ids = [item.strip() for item in raw_selection.split(",") if item.strip()]
    if not selected_ids:
        raise SystemExit("selection must include at least one FR id")
    for function_requirement_id in selected_ids:
        if not FR_ID_RE.fullmatch(function_requirement_id):
            raise SystemExit(
                f"invalid function_requirement_id in selection: {function_requirement_id}"
            )
    return selected_ids


def existing_story_matches(project_root: Path, parent: str) -> dict[str, list[str]]:
    user_story_dir = project_root / "docs" / "user-stories"
    matches: dict[str, list[str]] = {}
    if not user_story_dir.exists():
        return matches

    for doc_path in sorted(user_story_dir.glob("*.md")):
        front_matter = read_front_matter(doc_path)
        if front_matter.get("parent") != parent:
            continue
        function_requirement_id = front_matter.get("function_requirement_id")
        if not function_requirement_id:
            continue
        matches.setdefault(function_requirement_id, []).append(str(doc_path.resolve()))
    return matches


def build_plan(
    project_root: Path,
    parent: str,
    selected_ids: list[str],
) -> list[dict[str, object]]:
    matches_by_requirement = existing_story_matches(project_root, parent)
    plan: list[dict[str, object]] = []
    for function_requirement_id in selected_ids:
        existing_paths = matches_by_requirement.get(function_requirement_id, [])
        role = "patch" if existing_paths else "main"
        plan.append(
            {
                "function_requirement_id": function_requirement_id,
                "role": role,
                "existing_story_paths": existing_paths,
            }
        )
    return plan


def main() -> int:
    args = parse_args()
    if not PRD_ID_RE.fullmatch(args.parent):
        raise SystemExit("parent must be a PRD id like prd-00020-checkout-redesign")

    project_root = find_project_root(Path.cwd())
    prd_path = project_root / "docs" / "prds" / f"{args.parent}.md"
    if not prd_path.exists():
        raise SystemExit(f"parent PRD not found: {prd_path}")

    available_ids = extract_function_requirement_ids(prd_path)
    if args.select_all:
        selected_ids = available_ids
        selection_mode = "all"
    elif args.select:
        selected_ids = parse_selection(args.select)
        invalid_ids = [item for item in selected_ids if item not in available_ids]
        if invalid_ids:
            raise SystemExit(
                f"unknown FR ids for {args.parent}: {', '.join(invalid_ids)}"
            )
        selection_mode = "subset"
    else:
        selected_ids = []
        selection_mode = "unselected"

    payload = {
        "parent": args.parent,
        "prd_path": str(prd_path.resolve()),
        "available_function_requirement_ids": available_ids,
        "selection_mode": selection_mode,
        "selected_function_requirement_ids": selected_ids,
        "plan": build_plan(project_root, args.parent, selected_ids),
    }
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 3: Add a duplicate-story fixture for planner verification**

Create `skills/toad-copilot/writing-user-story/evals/files/docs/user-stories/us-00001-existing-guest-checkout-without-account.md` with this content:

```markdown
---
id: us-00001-existing-guest-checkout-without-account
type: us
role: main
status: active
parent: prd-00020-checkout-redesign
function_requirement_id: FR-01
---

# User Story

As a guest shopper,
I want to place an order without creating an account,
So that I can finish checkout quickly on my first visit.

# Acceptance Criteria
- [ ] AC-01: Given a shopper reaches checkout without an account, when they submit valid shipping and payment details, then the order is placed without requiring registration.
- [ ] AC-02: Given a shopper completes guest checkout, when the order succeeds, then the order is associated with the email address entered during checkout.
- [ ] AC-03: Given a shopper starts guest checkout, when they review the form, then account creation is optional rather than required.

# Definition of Done
- [ ] Implemented
- [ ] Documented
- [ ] Tested
```

- [ ] **Step 4: Run the planner against the base fixture and verify FR listing**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story/evals/files
python3 ../../scripts/plan_user_stories.py --parent prd-00020-checkout-redesign
```

Expected: PASS with JSON containing:

```json
{
  "selection_mode": "unselected",
  "available_function_requirement_ids": ["FR-01", "FR-02", "FR-03"],
  "selected_function_requirement_ids": [],
  "plan": []
}
```

- [ ] **Step 5: Run the planner against the duplicate fixture and verify patch planning**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story/evals/files
python3 ../../scripts/plan_user_stories.py --parent prd-00020-checkout-redesign --select FR-01
```

Expected: PASS with JSON containing:

```json
{
  "selection_mode": "subset",
  "selected_function_requirement_ids": ["FR-01"],
  "plan": [
    {
      "function_requirement_id": "FR-01",
      "role": "patch"
    }
  ]
}
```

- [ ] **Step 6: Commit**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
git add skills/toad-copilot/writing-user-story/scripts/plan_user_stories.py skills/toad-copilot/writing-user-story/evals/files/docs/user-stories/us-00001-existing-guest-checkout-without-account.md
git commit -m "feat(skill): add user story planner"
```

Expected: PASS with a commit containing the new planner script and duplicate fixture.

### Task 2: Update The Skill Workflow

**Files:**
- Modify: `skills/toad-copilot/writing-user-story/SKILL.md`

- [ ] **Step 1: Write the failing workflow checks**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
rg -n "list the PRD's FRs|sequential|parallel|execution plan|duplicate" skills/toad-copilot/writing-user-story/SKILL.md
```

Expected: FAIL to find the new workflow language because the current skill still assumes one FR can be matched directly.

- [ ] **Step 2: Replace the defaults and workflow sections with the explicit-selection flow**

Update `skills/toad-copilot/writing-user-story/SKILL.md` so the `## Defaults` and `## Workflow` sections read:

```markdown
## Defaults

- Require an explicit parent PRD id before generating any user story.
- Do not infer an FR from feature text or a plain-language description.
- When explicit FR ids are missing, list the parent PRD's FRs and ask the user to choose `all` or an explicit subset such as `FR-01, FR-03`.
- When the user chooses `all`, ask whether to run `sequential` or `parallel`.
- Use a planning pass to compute `FR -> role` before generating any files.
- Treat any existing story with the same `parent + function_requirement_id` as a duplicate and create the new story with `role: patch`.
- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- Keep each user story short and concrete.

## Workflow

1. Identify the parent PRD id.
2. If the parent PRD id is missing, ask the user to provide it before doing any other work.
3. Run:
   `python3 scripts/plan_user_stories.py --parent <prd-id>`
   Resolve `scripts/plan_user_stories.py` relative to this skill directory.
4. If the user did not provide explicit FR ids, list the available FR ids from the planner output and ask the user to choose `all` or an explicit subset like `FR-01, FR-03`.
5. If the user chooses `all`, ask whether to run `sequential` or `parallel`.
6. If the user provides an explicit subset, rerun:
   `python3 scripts/plan_user_stories.py --parent <prd-id> --select <comma-separated-fr-ids>`
7. If the user chooses `all`, rerun:
   `python3 scripts/plan_user_stories.py --parent <prd-id> --all`
8. If the planner reports any invalid FR ids, reject the whole selection and ask the user to choose again.
9. Show a short terminal-only execution plan such as `FR-01 -> main` and `FR-03 -> patch`.
10. Ask for confirmation before generating any files.
11. In sequential mode, generate each planned story one by one by running:
    `python3 scripts/create_user_story.py "<slug or title>" --json --parent <prd-id> --function-requirement-id <FR-id> --role <main|patch> [--status draft|active|archived]`
12. In parallel mode, spawn one worker sub-agent per planned FR. Each worker owns a single FR, uses the planned role from the execution plan, runs `create_user_story.py`, fills the scaffold, validates the result, and reports success or failure without reverting other workers' changes.
13. If one story fails, retry that story exactly once.
14. After the retry, continue processing the remaining planned FRs.
15. Report which stories succeeded and which stories still failed.
```

- [ ] **Step 3: Update the generated-file section so it still matches the single-story writer**

Make sure the `## Generated File` section stays single-story and reads:

```markdown
## Generated File

By default the script saves to `docs/user-stories` in the current project. Use `--output-dir` only
when the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, `parent`, and `function_requirement_id`
- the `# User Story` document
- these sections:
  `User Story`, `Acceptance Criteria`, and `Definition of Done`

Use the planner to decide which FR ids to generate and which role each story should use before invoking the single-story scaffold generator.
```

- [ ] **Step 4: Run the workflow checks again**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
rg -n "list the parent PRD's FRs|sequential|parallel|execution plan|duplicate|retry that story exactly once" skills/toad-copilot/writing-user-story/SKILL.md
```

Expected: PASS with matches in the updated defaults and workflow sections.

- [ ] **Step 5: Commit**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
git add skills/toad-copilot/writing-user-story/SKILL.md
git commit -m "feat(skill): require explicit fr selection"
```

Expected: PASS with a commit containing only the skill workflow update.

### Task 3: Expand Eval Fixtures And Prompts

**Files:**
- Modify: `skills/toad-copilot/writing-user-story/evals/evals.json`
- Reuse: `skills/toad-copilot/writing-user-story/evals/files/docs/prds/prd-00020-checkout-redesign.md`
- Reuse: `skills/toad-copilot/writing-user-story/evals/files/docs/user-stories/us-00001-existing-guest-checkout-without-account.md`

- [ ] **Step 1: Write the failing eval coverage check**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
rg -n "missing the parent PRD|list the available FR ids|sequential or parallel|role it will use" skills/toad-copilot/writing-user-story/evals/evals.json
```

Expected: FAIL because the current eval set only covers direct single-FR creation.

- [ ] **Step 2: Replace the eval set with prompts that exercise the new checkpoints**

Update `skills/toad-copilot/writing-user-story/evals/evals.json` so it contains these evals:

```json
{
  "skill_name": "writing-user-story",
  "evals": [
    {
      "id": 1,
      "prompt": "Write a user story in docs/user-stories for FR-01 in prd-00020-checkout-redesign about letting shoppers complete checkout without creating an account.",
      "expected_output": "A new main user story saved under docs/user-stories with parent prd-00020-checkout-redesign, function_requirement_id FR-01, and the standard user story template filled in.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md"
      ],
      "expectations": [
        "The run creates exactly one new markdown file under docs/user-stories.",
        "The new file front matter has type us, role main, status draft, parent prd-00020-checkout-redesign, and function_requirement_id FR-01.",
        "The new file includes the User Story, Acceptance Criteria, and Definition of Done sections with at least three AC-xx checklist items."
      ]
    },
    {
      "id": 2,
      "prompt": "Create an active user story in docs/user-stories for FR-02 in prd-00020-checkout-redesign about routing high-risk guest checkout orders to manual review.",
      "expected_output": "A new main user story saved under docs/user-stories with status active, parent prd-00020-checkout-redesign, function_requirement_id FR-02, and clear acceptance criteria.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md"
      ],
      "expectations": [
        "The run creates exactly one new markdown file under docs/user-stories.",
        "The new file front matter has type us, role main, status active, parent prd-00020-checkout-redesign, and function_requirement_id FR-02.",
        "The new file includes the User Story, Acceptance Criteria, and Definition of Done sections with at least three AC-xx checklist items."
      ]
    },
    {
      "id": 3,
      "prompt": "Write a user story in docs/user-stories for the guest checkout redesign. I did not include the parent PRD yet, so ask me for it and stop.",
      "expected_output": "A reply that asks for the parent PRD id and creates no user story file.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md"
      ],
      "expectations": [
        "The run does not create any markdown files under docs/user-stories.",
        "The final reply asks the user to provide the parent PRD id.",
        "The final reply does not guess a functional requirement from feature text."
      ]
    },
    {
      "id": 4,
      "prompt": "Use prd-00020-checkout-redesign to write user stories, but I have not picked any FR ids yet. List the available FR ids and ask me to choose all or a subset.",
      "expected_output": "A reply that lists FR-01 through FR-03, asks the user to choose all or a subset, and creates no user story file.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md"
      ],
      "expectations": [
        "The run does not create any markdown files under docs/user-stories.",
        "The final reply lists FR-01, FR-02, and FR-03 from the parent PRD.",
        "The final reply asks the user to choose all or an explicit subset of FR ids."
      ]
    },
    {
      "id": 5,
      "prompt": "Use prd-00020-checkout-redesign to write user stories for all FRs. Do not create files yet; ask me whether to run sequential or parallel.",
      "expected_output": "A reply that asks the user to choose sequential or parallel and creates no user story file.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md"
      ],
      "expectations": [
        "The run does not create any markdown files under docs/user-stories.",
        "The final reply asks whether to run sequential or parallel.",
        "The final reply does not skip directly to file generation."
      ]
    },
    {
      "id": 6,
      "prompt": "Use prd-00020-checkout-redesign and FR-01. Show me the execution plan and tell me what role it will use before you create anything.",
      "expected_output": "A reply that shows an execution plan with FR-01 planned as patch because an existing FR-01 story is already present.",
      "files": [
        "evals/files/AGENTS.md",
        "evals/files/docs/prds/prd-00020-checkout-redesign.md",
        "evals/files/docs/user-stories/us-00001-existing-guest-checkout-without-account.md"
      ],
      "expectations": [
        "The run does not create any markdown files under docs/user-stories beyond the existing fixture file.",
        "The final reply shows an execution plan for FR-01.",
        "The execution plan marks FR-01 with role patch."
      ]
    }
  ]
}
```

- [ ] **Step 3: Validate the JSON and spot-check the new prompts**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
python3 -m json.tool skills/toad-copilot/writing-user-story/evals/evals.json >/tmp/writing-user-story-evals.json
rg -n "\"id\": 3|\"id\": 4|\"id\": 5|\"id\": 6" skills/toad-copilot/writing-user-story/evals/evals.json
```

Expected:
- `python3 -m json.tool` PASS with no output to stderr.
- `rg` PASS with matches for the new interactive eval ids.

- [ ] **Step 4: Commit**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
git add skills/toad-copilot/writing-user-story/evals/evals.json
git commit -m "test(skill): expand writing-user-story evals"
```

Expected: PASS with a commit containing only the eval update.

### Task 4: Run The Skill-Creator Benchmark Loop

**Files:**
- Create: `skills/toad-copilot/writing-user-story-workspace/iteration-2/`
- Create: `skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/eval_metadata.json`
- Create: `skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/<configuration>/run-1/grading.json`
- Create: `skills/toad-copilot/writing-user-story-workspace/iteration-2/benchmark.json`
- Create: `skills/toad-copilot/writing-user-story-workspace/iteration-2/review.html`

- [ ] **Step 1: Create iteration metadata files for each eval**

For each eval id in `skills/toad-copilot/writing-user-story/evals/evals.json`, create:

```json
{
  "eval_id": 3,
  "eval_name": "missing-prd-stops-and-asks",
  "prompt": "Write a user story in docs/user-stories for the guest checkout redesign. I did not include the parent PRD yet, so ask me for it and stop.",
  "assertions": []
}
```

Use these eval names:

```text
1 -> explicit-single-fr-main
2 -> explicit-single-fr-active
3 -> missing-prd-stops-and-asks
4 -> missing-fr-lists-choices
5 -> all-selection-asks-for-mode
6 -> duplicate-fr-plans-patch
```

- [ ] **Step 2: Spawn with-skill and old-skill runs in the same turn**

Dispatch one with-skill run and one old-skill run for each eval. Use this with-skill prompt template:

```text
Execute this task:
- Skill path: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story
- Task: <eval prompt>
- Input files: <eval files list>
- Save outputs to: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/with_skill/outputs/
- Outputs to save: the final reply plus any created markdown files under docs/user-stories
```

Use this old-skill baseline template:

```text
Execute this task:
- Skill path: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/skill-snapshot
- Task: <eval prompt>
- Input files: <eval files list>
- Save outputs to: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/old_skill/outputs/
- Outputs to save: the final reply plus any created markdown files under docs/user-stories
```

Expected: all twelve runs are in flight before any grading work begins.

- [ ] **Step 3: Draft assertions while the runs are executing**

Add these assertions to the iteration metadata and to `evals/evals.json` where they apply:

```text
missing-prd-stops-and-asks
- The run does not create any markdown files under docs/user-stories.
- The final reply asks for the parent PRD id.
- The final reply does not guess an FR from feature text.

missing-fr-lists-choices
- The run does not create any markdown files under docs/user-stories.
- The final reply lists FR-01, FR-02, and FR-03.
- The final reply asks the user to choose all or a subset.

all-selection-asks-for-mode
- The run does not create any markdown files under docs/user-stories.
- The final reply asks whether to run sequential or parallel.
- The final reply does not skip directly to file generation.

duplicate-fr-plans-patch
- The run does not create any markdown files under docs/user-stories beyond the existing fixture file.
- The final reply shows an execution plan for FR-01.
- The execution plan marks FR-01 as patch.
```

- [ ] **Step 4: Grade each run before aggregating the benchmark**

For each run directory under `iteration-2`, dispatch a grader using this prompt:

```text
Read /Users/ryan/.agents/skills/skill-creator/agents/grader.md and grade this run.
- Run directory: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/<configuration>/run-1
- Eval metadata: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/eval_metadata.json
- Save result to: /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/<eval-name>/<configuration>/run-1/grading.json

Use the exact grading schema:
{
  "expectations": [
    {"text": "...", "passed": true, "evidence": "..."}
  ],
  "summary": {"passed": 0, "failed": 0, "total": 0, "pass_rate": 0.0}
}
```

Expected: every `with_skill` and `old_skill` run has a `grading.json` file before the aggregate step.

- [ ] **Step 5: Aggregate the benchmark and generate the review viewer**

Run:

```bash
cd /Users/ryan/.agents/skills/skill-creator
python -m scripts.aggregate_benchmark /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2 --skill-name writing-user-story
python eval-viewer/generate_review.py /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2 --skill-name writing-user-story --benchmark /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/benchmark.json --previous-workspace /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-1 --static /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/review.html
```

Expected:
- `benchmark.json` and `benchmark.md` exist in `iteration-2`.
- `review.html` exists in `iteration-2`.
- The viewer compares `with_skill` against `old_skill`.

- [ ] **Step 6: Review the results and decide whether another iteration is required**

Open:

```text
/Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot/skills/toad-copilot/writing-user-story-workspace/iteration-2/review.html
```

Expected: the review captures whether the revised skill now stops for missing PRDs, lists FRs instead of guessing, asks for sequential versus parallel when the user chooses all, and plans duplicate FRs as `patch`.

- [ ] **Step 7: Commit the implementation after the benchmark looks correct**

Run:

```bash
cd /Users/ryan/GitHubProjects/ryan-alexander-zhang/toad-copilot
git status --short
git add skills/toad-copilot/writing-user-story
git commit -m "feat(skill): add explicit user story planning flow"
```

Expected: PASS with a final commit containing the planner script, updated skill instructions, and expanded evals.

# Writing User Story FR Selection And Batch Generation

## Summary

Update `writing-user-story` so it no longer guesses the target functional requirement from feature text. The revised flow requires an explicit parent PRD, lets the user choose `all` or an explicit `FR-xx` subset from that PRD, shows a short execution plan before writing, and supports sequential or parallel generation for multi-story runs.

## Goals

- Require an explicit parent PRD before the skill can generate any user story.
- Remove feature-name inference and replace it with explicit `FR-xx` selection.
- Support generating user stories for either a selected subset or all FRs in a PRD.
- Make `main` versus `patch` role assignment deterministic from existing story files.
- Keep generation resilient by retrying per-story failures once and reporting remaining failures at the end.

## Non-Goals

- Redesign the user story document schema or front matter fields.
- Replace the existing single-story scaffold generator with a batch writer.
- Infer a likely FR from free-form feature descriptions.
- Make the multi-story run transactional.

## Assumptions

- `docs/specs` is the correct location for engineering specs in this repository.
- The existing `create_user_story.py` and `validate_doc.py` scripts remain single-story utilities.
- The runtime can spawn one worker sub-agent per FR when the user chooses parallel execution.

## Required Inputs

- `parent` PRD id is required. If missing, stop and ask the user to provide it.
- Explicit `FR-xx` ids are optional at the start of the request.
- `sequential` versus `parallel` is only needed when the user chooses `all`.

## Interaction Rules

1. If the user does not provide a parent PRD id, stop and ask for it.
2. If the user provides explicit `FR-xx` ids, validate them against the parent PRD.
3. If the user does not provide explicit `FR-xx` ids, list the PRD's FRs and ask the user to choose either `all` or an explicit subset such as `FR-01, FR-03`.
4. Do not infer the FR from a feature description or plain-language request.
5. If the user chooses `all`, ask whether to run `sequential` or `parallel`.
6. If the user provides any invalid FR in a subset, reject the whole selection and ask again.
7. Before writing files, show a short terminal-only execution plan such as `FR-01 -> main` and `FR-03 -> patch`.
8. Ask for confirmation after showing the execution plan.

## Planning Rules

- Extract valid FR ids from the parent PRD.
- Determine whether each selected FR should create a `main` or `patch` story by scanning existing user stories.
- Treat any existing story with the same `parent + FR` as a duplicate for role selection.
- When a duplicate exists, the new story must use `role: patch`.
- When no duplicate exists, the new story must use `role: main`.

## Generation Rules

- Generate one story per selected FR.
- In sequential mode, the main agent processes the FRs one by one.
- In parallel mode, spawn one worker sub-agent per selected FR.
- Each story generation attempt uses the existing single-story scaffold and validation flow.
- If one story fails, retry that story exactly once.
- After the retry, continue processing the rest of the batch even if the story still fails.
- At the end of the batch, report which stories succeeded and which still failed.

## Implementation Split

### `SKILL.md`

- Update the trigger description if needed so the skill is still discoverable for user-story requests.
- Replace the current FR matching flow with the explicit PRD and FR selection flow.
- Document the execution-plan confirmation step.
- Document the sequential versus parallel branch for `all`.
- Document the duplicate-to-`patch` rule.

### `scripts/plan_user_stories.py`

Create a helper script with one responsibility: plan a requested user-story run without writing any story files.

The script should:

- read the parent PRD
- extract all valid `FR-xx` ids
- validate an explicit subset
- scan `docs/user-stories` for existing `parent + FR` matches
- compute the planned role for each selected FR
- emit machine-readable output that the skill can use to present the execution plan

### `scripts/create_user_story.py`

- Keep the script focused on creating one story file at a time.
- Continue accepting the chosen role and a single `function_requirement_id`.
- Do not add batch orchestration to this script.

### `scripts/validate_doc.py`

- Keep validation focused on a single story file.
- Continue validating the front matter, parent PRD link, and existence of the referenced FR.

## Data Flow

1. Read the parent PRD.
2. Build the list of valid FR ids.
3. Collect the user's selection.
4. Plan the run by computing `FR -> role`.
5. Present the execution plan and wait for confirmation.
6. Generate each selected story using the existing single-story path.
7. Validate each generated story.
8. Retry each failed story once.
9. Report final per-FR status.

## Error Handling

- Missing PRD: stop immediately and ask for the PRD id.
- Unknown PRD: stop and report that the PRD file was not found.
- Invalid FR subset: reject the whole subset and ask again.
- Empty PRD FR list: stop and report that no functional requirements were found.
- Planning failure for one FR: do not start generation until planning succeeds for the full requested selection.
- Generation failure for one FR: retry once, continue the batch, then report the final failure.

## Evals And Verification

Expand `skills/toad-copilot/writing-user-story/evals/evals.json` to cover both the old and new paths:

- explicit single FR still creates one story with the requested status
- explicit duplicate `parent + FR` produces a `patch` role
- missing FR with explicit PRD lists all PRD FRs and asks for `all` or a subset
- invalid subset rejects the whole selection
- `all` asks for `sequential` versus `parallel`
- execution plan shows the planned role per FR before writing
- batch generation continues after one per-story failure and reports the remaining failure after one retry

Verification for the implementation should include:

- helper-script checks for FR extraction and role planning
- skill evals that compare the revised skill against the current behavior
- confirmation that parallel mode spawns one worker sub-agent per selected FR

## Success Criteria

- The skill never guesses an FR from feature text.
- A request without a PRD always stops for PRD selection.
- A request without explicit FR ids always shows the PRD's FRs and asks the user to choose.
- Duplicate `parent + FR` planning always resolves to `role: patch`.
- Multi-story runs continue after individual failures and report remaining failures clearly.

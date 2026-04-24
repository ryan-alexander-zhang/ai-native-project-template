---
id: spec-00001-project-bootstrap-and-managed-resource-mvp
type: spec
role: main
status: draft
parent: prd-00001-project-bootstrap-copilot
---

# Project bootstrap and managed-resource MVP

## Scope

This spec defines the MVP behavior for bootstrapping a local project and managing project-scoped
resources for AI coding workflows.

It covers:

- bootstrapping a new or empty local project
- applying managed setup to an existing local project
- creating, inspecting, and updating managed preset files, skills, and reusable resources
- the boundary between the reusable workflow core and the CLI adapter
- the result and failure model needed to verify those workflows

It does not define a hosted service, a web or desktop frontend, a general project-management
system, or a full implementation task plan.

## System Shape

The MVP has two product layers:

- `Headless workflow core`: the primary behavior surface. It owns project inspection, change
  planning, managed-resource application, and workflow result reporting. It must be callable
  without invoking the CLI.
- `CLI adapter`: the MVP entry point for terminal users. It owns argument parsing, workflow
  invocation, terminal rendering, and exit signaling. It must not contain bootstrap rules,
  managed-resource decision logic, or template ownership rules.

The core operates on a target project path plus the selected managed definitions for the run. It
does not require a database, queue, remote service, or global application state.

## Workflow Modes

The core must support these workflow modes:

- `bootstrap-new`: initialize a new or empty target with the standard directory structure,
  standard document structure, and selected managed resources.
- `bootstrap-existing`: inspect an existing local repository and standardize managed items in
  place instead of creating a separate scaffold.
- `manage-resources`: add, inspect, or update managed preset files, project-level skills, and
  reusable resources within an existing target project.

Each workflow follows the same lifecycle:

1. `Inspect`
   Determine whether the target should be treated as a new or existing project, discover the
   managed items already present, and load the selected managed definitions.
2. `Plan`
   Produce an explicit change set for each selected managed item: `created`, `updated`,
   `standardized`, `unchanged`, or `skipped`.
3. `Apply`
   Execute only the managed changes from the plan.
4. `Report`
   Return a structured result that describes what changed, what was skipped, and whether the run
   succeeded, partially succeeded, or failed.

## Managed Resource Rules

- The tool may only create or update the directories, files, skills, and resources represented by
  the selected managed definitions for the run.
- Existing unmanaged repository content must remain untouched unless it directly blocks a managed
  change.
- Preset project files are definition-driven. `AGENTS.md` is a key example, but the workflow must
  not be designed around a single hard-coded file.
- Rerunning the same workflow against the same target should converge on the same managed state
  rather than create duplicates or accumulate drift.
- For existing projects, the reported outcome must distinguish between items that were newly
  created and items that were standardized in place.

## Core Contract

Each core workflow call must receive:

- a target project path
- a workflow mode
- the selected managed definitions that the run is allowed to manage

Each workflow call must return a structured result with:

- overall status: `success`, `partial`, or `failed`
- detected target type: `new` or `existing`
- per-item action results: `created`, `updated`, `standardized`, `unchanged`, or `skipped`
- blocking errors tied to the managed item that caused them
- a summary that an interface layer can render directly

The structured result is the stable contract between the core and the CLI. Tests should assert on
that result in addition to filesystem outcomes.

## Failure Model

- If the target path is invalid, inaccessible, or otherwise unusable, the workflow must fail
  before making changes.
- If required managed definitions are unavailable, the workflow must fail before making changes.
- If a managed item fails during apply, the result must identify which item failed and whether any
  earlier managed changes already succeeded.
- Existing unmanaged files are not a failure by themselves. They become blocking only when they
  prevent a managed change from completing.
- The tool must prefer explicit skip or block reporting over silent overwrite behavior.

The MVP does not guarantee full rollback across multiple file operations. Partial application is
allowed as long as the result makes the partial state visible and testable.

## CLI Surface

The CLI is a thin adapter over the core workflows. The MVP command surface should be organized as:

- `bootstrap new`
- `bootstrap existing`
- `resources`

CLI behavior requirements:

- Each command maps directly to a core workflow instead of reimplementing workflow logic.
- The CLI renders a result summary that makes `created`, `updated`, `standardized`, `unchanged`,
  and `skipped` visible to the user.
- Successful runs return a success exit path. Blocking failures return a failure exit path.
- A `partial` workflow result must return a failure exit path so automation can treat incomplete
  application conservatively.
- The CLI may shape input and presentation, but it must not own managed definitions or filesystem
  decision rules that belong in the core.

Any workflow behavior needed by the CLI must exist in the core first so that a future frontend can
reuse the same operations without duplicating decision logic.

## Verification

The implementation of this spec must be verifiable through:

- core workflow tests that cover inspect, plan, apply, and report behavior for all three workflow
  modes
- CLI adapter tests that cover representative commands, rendered summaries, and success or failure
  exit behavior
- manual verification for generated or updated Markdown docs and repo-scoped skill assets to
  confirm that managed files match the selected definitions and unrelated files were not changed

Verification must include these cases:

- invalid target path
- missing managed definitions
- rerun on an already standardized target
- existing project with only some managed resources present
- partial apply failure with visible reporting

## Non-Goals

- hosted services or remote synchronization
- desktop or web frontends
- general project-management or issue-tracking features
- full transactional rollback across multiple file operations
- managing every file in a repository instead of only the selected managed resources

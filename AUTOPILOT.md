# Autopilot

## Purpose

An unattended run from a one-line idea prompt to a review-ready PR. Entry is the
`autopilot` skill (`/autopilot <prompt>`), or asking any agent to "run
AUTOPILOT.md with: <prompt>".

The stages follow the product flow [docs/README.md](docs/README.md) and
`rule-00001` define, and every root guide applies as written. This file lists only
what autopilot adds: the intake round, the human rounds it stands in for, the
decision trail, the ledger, the stop conditions.

## Intake — the only human round

1. Split the prompt into settled items (e.g. "use Go") and open items.
2. Unless the prompt says `--no-ask`, ask one round covering only the open items
   (the question list is in the skill). "You decide" hands an item to the agent.
3. Create branch `autopilot/<slug>` from the current branch.
4. Write the `idea` doc from the prompt and the answers — scope in 范围, settled
   constraints in 已定方向. Items still open (always the case under `--no-ask`)
   are closed by decisions as below. Set it `active`, create the ledger, commit.
   From here on the run asks nothing.

## Human rounds replaced

| As written | In an autopilot run |
| --- | --- |
| `AGENTS.md` §1: if uncertain, ask | Decide. A choice that clears `docs/decision/README.md`'s Good Fit bar becomes a `decision` with `decided_by: agent`, cited where the question arose; a smaller one is a ledger line |
| `AGENTS.md` §5: a term conflicting with `CONTEXT.md` — stop | Resolve it as a decision, update `CONTEXT.md`, continue |
| `AGENTS.md` §6: what only a domain owner can settle is never an assumption | Still never an assumption — it becomes a `decision` with `decided_by: agent`, so the owner can find and overturn it |
| `DOCUMENT.md`: a doc with open questions stays `draft` | Each open question is closed by a `decision` before the doc is promoted; the Open Questions section is deleted |
| `DOCUMENT.md`: ask if a draft is reviewed, then promote | For `spec` / `rule` / `design`: the audit (`AGENTS.md` §6) with zero unresolved findings. For `prd` and `plan`, which `rule-00001-BR-23` keeps outside the audit: a pre-promotion check by a subagent that did not write it, against the folder README |
| Harness default: commit only when asked | The invocation authorises commits on `autopilot/<slug>`, per `COMMIT.md`. Never commit to `main`, never merge, never force-push |
| `PR.md` / `REVIEW.md`: human review before merge | Not replaced. The run ends at an open PR |

## Decisions

- One `decision` per question. `decided_by: human` when the choice came from the
  prompt or an intake answer; `decided_by: agent` otherwise. The field is defined
  in [docs/README.md](docs/README.md).
- A decision is promoted to `active` in the stage that wrote it, with
  `motivated_by` naming the doc that raised the question and `constrains` the
  docs it binds.
- Technology choices follow this priority: prompt and intake constraints > the
  repo's existing stack and `active` decisions > ecosystem maturity and
  maintenance activity. Web lookup is allowed to verify versions; cite what was
  consulted.

## Agents

Two roles do the work; the orchestrating session only reads and writes the
ledger and dispatches. A role is a contract on what it may write and how strong
its model must be. Model names never appear in this file: each harness binds the
roles to its own models (see Bindings), so the same run reads the same under any
harness.

| Role | Runs | May write | Model |
| --- | --- | --- | --- |
| `doc-agent` | `intake`, `prd`, `architecture`, `spec`, `plan`, `pr`; in `acceptance` the GWT verification, the code review and the `record`; every audit and pre-promotion check; every `decision`; every `issue` doc | anything | not weaker than the orchestrator's |
| `code-agent` | `implement`, task by task; in `acceptance` the manual or browser smoke runs and the fixes for review findings | code, tests, config and build files the tasks name; its own ledger lines | may be one tier below the orchestrator's |

- `code-agent` never creates or edits a file under `docs/`. When a task meets a
  defect or an ambiguity, it writes what it found into the ledger and returns;
  the orchestrator dispatches `doc-agent` for the `issue` or `decision`, then
  resumes the task with `code-agent`.
- `doc-agent` is the only role that stands in for a human round (Human rounds
  replaced). It is never bound to a model below the orchestrator's — a cheaper
  gate is no gate.
- A harness without subagents, or without per-role model binding, runs every
  stage in the orchestrator's own context at `doc-agent` strength. The fallback
  is always the stronger side.
- Each ledger stage row records the role and the model that actually ran it.

### Bindings

| Harness | Where the roles are bound |
| --- | --- |
| Claude Code | `.claude/agents/autopilot-doc.md`, `.claude/agents/autopilot-code.md` (`model` in the front matter) |
| Codex | `.codex/agents/autopilot-doc.toml`, `.codex/agents/autopilot-code.toml` (`model` and `model_reasoning_effort`). Do not set `agents.default_subagent_model` in any `config.toml`: it overrides the role files |
| any other | add a row here before the first run; until then, fallback above |

## Stages

Each stage runs in a fresh context under the role the Agents table assigns it.
Each stage ends with a commit. A stage the idea does not need is marked `n/a` in the
ledger with a one-line reason. Ledger row names:

1. `intake` — branch, `idea` active, ledger.
2. `prd` — the `idea` completed into a `prd` (audience, scope, non-functional
   requirements, constraints); checked; `active`.
3. `architecture` — technology selection: one `decision` per choice, `active`.
   In an existing project, only what `ARCHITECTURE.md` and the `active`
   decisions do not already cover.
4. `spec` — `spec`, its `rule` and `design` docs (the system-level design
   included, `informs` set), acceptance per `ACCEPTANCE.md`, `CONTEXT.md`
   terms; audited; `active`. Then the root guides are filled per `AGENTS.md`
   §8.
5. `plan` — the `plan`; checked; `open`.
6. `implement` — task by task per `DEVELOPMENT.md` and `TESTING.md`; a defect
   found on the way gets its `issue` doc first (`docs/issue/README.md`) and is
   fixed within the run. Exit: the `DEVELOPMENT.md` and `TESTING.md`
   Definitions of Done hold.
7. `acceptance` — the verification subagent, one `doc-agent` code review of
   the run's whole diff per `REVIEW.md` (findings are fixed by `code-agent`
   before the stage ends), the smoke runs `TESTING.md` asks for (`code-agent`,
   results written into the ledger), and the `record` acceptance checklist;
   `plan` -> `resolved` (`AGENTS.md` §8, last bullet).
8. `pr` — open the PR per `PR.md` against the branch the run started from. The
   PR body links the ledger and lists every `decided_by: agent` decision. Stop.

## Ledger

`.autopilot/<slug>.md`, outside `docs/` on purpose: a live progress file is not a
document of record, so it needs no front matter and no exception to any rule. It
is the run's single source of progress and the reviewer's index afterwards. Body:

- a stage table: stage | progress (`pending` / `running` / `done` / `n/a` /
  `blocked`) | agent (role and the model that ran it) | docs produced (ids);
- a decisions list: id, `decided_by`, the choice in one line;
- a Blocked section: empty until a stop condition fires.

`/autopilot resume <slug>` reads the ledger and continues from the first row not
`done` or `n/a`.

## Stop conditions

Write the reason into the Blocked section, commit the ledger, and stop. Docs in
flight stay uncommitted in the working tree, listed by path in Blocked. Never
lower a threshold or suppress a finding to get past a gate (`CODE_QUALITY.md`).

- The next step is destructive outside the run's own branch: force-push, merging,
  deleting files the run did not create, dropping data.
- The next step touches secrets, credentials, payment, or publishes anything
  outside this repo.
- The same gate fails twice in a row for the same root cause with no effective
  change between the attempts.
- An `issue` cannot be fixed within the plan's scope.
- A root guide requires a human approval the run cannot obtain: the exception to
  `TESTING.md`'s completion bar, anything `SECURITY.md` escalates.
- The harness has no row in Bindings and cannot run at `doc-agent` strength, or
  a `doc-agent` stage would run on a model below the orchestrator's.

## Done

The `plan` is `resolved`, the PR is open, and every decision written during the
run is listed in the ledger and the PR body.

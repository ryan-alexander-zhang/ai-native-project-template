# Autopilot

## Purpose

An unattended run from a one-line idea prompt to a review-ready PR. Entry:
the `autopilot` skill (`/autopilot <prompt>`) or "run AUTOPILOT.md with:
<prompt>".

Stages follow the product flow in [docs/README.md](docs/README.md); every root
guide applies as written. This file lists only what autopilot adds: intake, the
human rounds it stands in for, the decision trail, the ledger, the stop
conditions.

## Intake — the only human round

1. Split the prompt into settled items ("use Go") and open items.
2. Unless the prompt says `--no-ask`, ask one round on the open items only
   (question list in the skill). "You decide" hands an item to the agent.
3. Create branch `autopilot/<slug>` from the current branch.
4. Write the `idea` doc: scope in Scope, settled constraints and every profile
   fact in Constraints. Items still open (always under `--no-ask`) are closed
   by decisions below. Set `active`, create the ledger, commit. From here on
   the run asks nothing.

## Human rounds replaced

| As written | In an autopilot run |
| --- | --- |
| `AGENTS.md` §1: if uncertain, ask | Decide. A choice clearing `docs/decision/README.md`'s Good Fit bar is a `decision` with `decided_by: agent`, cited where the question arose; a smaller one is a ledger line |
| `AGENTS.md` §5: a term conflicting with `CONTEXT.md` — stop | Resolve as a decision, update `CONTEXT.md`, continue |
| `AGENTS.md` §6: what only a domain owner can settle is never an assumption | Still never an assumption: a `decision` with `decided_by: agent`, so the owner can find and overturn it |
| `DOCUMENT.md`: a doc with open questions stays `draft` | Each open question is closed by a `decision` before promotion; the Open Questions section is deleted |
| `DOCUMENT.md`: ask if a draft is reviewed, then promote | `spec` / `rule` / `quality` / `design`: the `AGENTS.md` §6 audit with zero unresolved findings. `prd` / `plan`: a pre-promotion check by a subagent that did not write it, against the folder README |
| Harness default: commit only when asked | The invocation authorises commits on `autopilot/<slug>`, per `COMMIT.md`. Never commit to `main`, merge, or force-push |
| `PR.md` / `REVIEW.md`: human review before merge | Not replaced. The run ends at an open PR |

## Decisions

- One `decision` per question. `decided_by: human` when the choice came from the
  prompt or intake; `decided_by: agent` otherwise (field defined in
  [docs/README.md](docs/README.md)).
- Promoted to `active` in the stage that wrote it; `motivated_by` names the doc
  that raised the question, `constrains` the docs it binds.
- Technology priority: prompt and intake constraints > existing stack and
  `active` decisions > ecosystem maturity and maintenance. Web lookup allowed to
  verify versions; cite what was consulted.

## Agents

Two roles do the work; the orchestrator only reads and writes the ledger and
dispatches. A role is a contract on what it may write and how strong its model
must be. Model names never appear here: each harness binds roles to models
(Bindings).

| Role | Runs | May write | Model |
| --- | --- | --- | --- |
| `doc-agent` | `intake`, `prd`, `quality`, `architecture`, `spec`, `plan`, `pr`; in `acceptance` the GWT and QS verification, the code review and the `record`; every audit and pre-promotion check; every `decision`; every `issue` | anything | not weaker than the orchestrator's |
| `code-agent` | `implement`, task by task; in `acceptance` the smoke, `load` and `chaos` runs, and the fixes for review findings | code, tests, load and chaos scripts, config and build files the tasks name; its own ledger lines | may be one tier below the orchestrator's |

- `code-agent` never writes under `docs/`. On a defect or ambiguity it writes
  the finding to the ledger and returns; the orchestrator dispatches
  `doc-agent` for the `issue` or `decision`, then resumes the task.
- Only `doc-agent` stands in for a human round; a cheaper gate is no gate.
- A harness without subagents or per-role binding runs every stage in the
  orchestrator's context at `doc-agent` strength.
- Each ledger stage row records the role and the model that ran it.

### Bindings

| Harness | Where the roles are bound |
| --- | --- |
| Claude Code | `.claude/agents/autopilot-doc.md`, `.claude/agents/autopilot-code.md` (`model` in the front matter) |
| Codex | `.codex/agents/autopilot-doc.toml`, `.codex/agents/autopilot-code.toml` (`model`, `model_reasoning_effort`). Never set `agents.default_subagent_model` in any `config.toml`: it overrides the role files |
| any other | add a row before the first run; until then, the fallback above |

## Stages

Each stage: fresh context, the role the Agents table assigns, ends with a
commit. A stage the idea does not need is `n/a` in the ledger with a one-line
reason. Ledger row names:

1. `intake` — branch, `idea` active, ledger.
2. `prd` — the `idea` completed into a `prd` (audience, scope, functional
   requirements, ranked quality goals, constraints); checked; `active`.
3. `quality` — first the profile (`QUALITY.md`, Profile), every dimension
   valued from `idea` and `prd`; an unsettled dimension takes the first value
   in its list, and all such defaults go into **one** `decision`
   (`decided_by: agent`, `motivated_by` the quality doc), listed first in the
   PR body as the run's top-line risk. Then the `prd`'s ranked Quality Goals
   refined into tagged `QR`s, each with six-part `QS`, method, stage, owing at
   least what the profile drives; an unsettled Measure is a `decision`, never a
   guess; audited; `active`.
4. `architecture` — technology selection: one `decision` per choice, naming in
   `motivated_by` the `QS` ids it serves and in §1 the profile dimensions that
   force it; every `decision` the Drives column obliges; `active`. In an
   existing project, only what `ARCHITECTURE.md` and `active` decisions do not
   cover. Reuse before build (`AGENTS.md` §2): a `decision` per new library, a
   `decision` per `build`.
5. `spec` — `spec` (§7 citing its scenarios), its `rule` and `design` docs
   (system-level design included; `informs` set; `implements` naming the
   scenarios each design realises), acceptance per `ACCEPTANCE.md`,
   `CONTEXT.md` terms; audited; `active`. `runtime` scenarios get their
   `operation` doc (SLI, SLO, alert, error budget policy), `active`. Then the
   root guides are filled per `AGENTS.md` §8, `PERFORMANCE_TESTING.md` /
   `RESILIENCE_TESTING.md` included when any scenario is `load` / `chaos`.
6. `plan` — `implements` takes the spec's §7 scenarios into scope; checked,
   every task sourced (`docs/plan/README.md`); `open`.
7. `implement` — task by task per `DEVELOPMENT.md` and `TESTING.md`; a defect
   gets its `issue` doc first (`docs/issue/README.md`) and is fixed within the
   run. Exit: the `DEVELOPMENT.md` and `TESTING.md` Definitions of Done hold.
8. `acceptance` — the verification subagent; one `doc-agent` code review of
   the whole diff per `REVIEW.md`, findings fixed by `code-agent`; the smoke
   runs `TESTING.md` asks for; the `load` / `chaos` runs of `release`
   scenarios (`code-agent`, results and report paths in the ledger); alert
   wiring for `runtime` scenarios (`code-agent`; `record` evidence = operation
   doc + alert rule location, per `QUALITY.md`); the `record` checklist;
   `plan` -> `resolved` (`AGENTS.md` §8, last two bullets).
9. `pr` — open the PR per `PR.md` against the starting branch. Body opens with
   the profile and the defaulted dimensions, links the ledger, lists every
   `decided_by: agent` decision. Stop.

## Ledger

`.autopilot/<slug>.md`, outside `docs/`: a live progress file is not a document
of record, so no front matter and no rule exceptions. Single source of progress
and the reviewer's index. Body:

- stage table: stage | progress (`pending` / `running` / `done` / `n/a` /
  `blocked`) | agent (role and model) | docs produced (ids);
- decisions list: id, `decided_by`, the choice in one line;
- Blocked section: empty until a stop condition fires.

`/autopilot resume <slug>` continues from the first row not `done` or `n/a`.

## Stop conditions

Write the reason into Blocked, commit the ledger, stop. Docs in flight stay
uncommitted, listed by path in Blocked. Never lower a threshold or suppress a
finding to pass a gate (`CODE_QUALITY.md`).

- Next step is destructive outside the run's branch: force-push, merge,
  deleting files the run did not create, dropping data.
- Next step touches secrets, credentials, payment, or publishes outside this repo.
- Same gate fails twice for the same root cause with no effective change between.
- An `issue` cannot be fixed within the plan's scope.
- A `release` scenario needs an environment the run cannot provision
  (production-sized data, a staging cluster) and its Environment line allows
  no substitute.
- A root guide requires a human approval: the exception to `TESTING.md`'s
  completion bar, anything `SECURITY.md` escalates.
- No Bindings row and cannot run at `doc-agent` strength, or a `doc-agent`
  stage would run below the orchestrator's model.

## Done

`plan` `resolved`, PR open, every decision of the run listed in the ledger and
the PR body.

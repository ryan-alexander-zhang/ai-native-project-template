# Docs System

The vocabulary of this template repository itself: the document system, its gates, and the tooling that keeps documents and code aligned. Downstream projects do not inherit this file (`template.json` excludes it) and write their own.

## Language

### Documents

**Doc**:
One file under `docs/<type>/` with front matter (`id`, `type`, `status`, relations). Defined in `docs/README.md`.
_Avoid_: document, page, artifact

**Living doc**:
A doc whose status runs `draft → active → archived` and whose `active` version is the current source of truth.
_Avoid_: reference doc, spec doc (as a generic term)

**Work item**:
A doc whose status runs `draft → open → resolved` (or `wontfix`): `issue`, `plan`, `task`.
_Avoid_: ticket, story (a story is a row in a spec, not a doc)

**Skeleton**:
The frozen, main-owned files that define the docs system: root guides, every `docs/**/README.md` and `TEMPLATE.md`, `scripts/trace-check`. Listed in `.template-sync.json`.
_Avoid_: framework, scaffold, docs-system files

**Instance doc**:
A doc that is not skeleton: a concrete `spec-00003-…`, `plan-…`, and so on. Free per project and per lang branch.
_Avoid_: content doc, real doc

**Revision round**:
The only way to change an `active` spec, rule, or design substantively: demote to `draft`, revise, audit, re-accept.
_Avoid_: amendment, edit in place, hotfix

### Requirements and acceptance

**Requirement item**:
One `spec-<n>-FR-<i>` (system requirement) or `rule-<n>-BR-<i>` (business rule). The unit that acceptance and plans point at.
_Avoid_: requirement (alone, when the item is meant), FR/BR (alone, without the doc prefix)

**AC**:
One acceptance criterion, `spec-<n>-AC-<i>.<k>` or `rule-<n>-AC-<i>.<k>`, written as a Given / When / Then block and attributed to one requirement item.
_Avoid_: GWT (that is the *format* of an AC, not the item), test case, scenario, example

**Delivery scope**:
The set of requirement items a plan's `implements` puts in scope, whose ACs must all be verified before the plan turns `resolved`.
_Avoid_: scope (alone), coverage

**Record**:
The `docs/record/` doc holding a plan's acceptance checklist: one row per AC, naming the test and result. Immutable once `active`.
_Avoid_: acceptance report, test report, evidence doc

**Resolved gate**:
The refusal of `open → resolved` while any AC in the delivery scope lacks a passing record row. Enforced by the board and by `scripts/trace-check`.
_Avoid_: DoD check, acceptance gate

### Traceability

**AC id suffix**:
`__spec_00001_AC_5_1` at the end of a test name: the AC id with `-` and `.` as `_`. The only carrier of an AC id in code.
_Avoid_: tag, annotation, marker, label

**Carried**:
An AC is carried when at least one tracked non-Markdown file contains a test name with its suffix.
_Avoid_: covered (that word is `trace-check`'s summary count), tested, linked

**Suspect**:
A record row whose `ac:<hash>` no longer matches the current text of its AC and requirement line. Failing in `trace-check`.
_Avoid_: stale, outdated, dirty

**Orphaned**:
An AC id suffix in code that names no existing AC (failing), or an AC carried by a test but in no plan's delivery scope (reported).
_Avoid_: dangling, unknown id

**Uncovered**:
An AC in a plan's delivery scope that no test carries. Failing on a `resolved` plan, reported on an `open` one.
_Avoid_: missing test, gap

**Board**:
The external whiteboard (the persimmon repository) that parses docs, runs the status transitions and the parse diagnostics. Configured by `whiteboard.config.yaml`.
_Avoid_: whiteboard, dashboard, 白板 and board mixed in one doc — pick `board` in English text, 白板 in Chinese text

**Boundary rule**:
One row of `ARCHITECTURE.md` §5 Boundaries: a dependency-direction, layering, or visibility rule with the assertion that enforces it.
_Avoid_: architecture rule, constraint (that word is arc42 §2), layer rule

### Branches and roles

**Lang branch**:
A `lang/<language>` branch that customizes the non-skeleton files for one language and receives skeleton changes by merge from `main`.
_Avoid_: language fork, variant, flavor

**Autopilot**:
The unattended idea-to-PR run defined in `AUTOPILOT.md`; its two roles are the **doc-agent** and the **code-agent**.
_Avoid_: auto mode, unattended mode, pipeline

## Flagged ambiguities

- **GWT vs AC** — `AGENTS.md` says "GWT ids"; the id belongs to the AC, GWT is its shape. Use "AC id". Fix the wording when that line is next touched.
- **Covered vs carried** — `trace-check` prints `COVERED n/m` for carried ACs. In prose say *carried*; keep `COVERED` only as the printed label.
- **Whiteboard / board / 白板** — three names for one thing across `README.md`, `docs/*/README.md`, and `whiteboard.config.yaml`. Canonical: board / 白板.

## Example dialogue

**Dev**: The plan is `resolved` but `trace-check` says spec-00003-AC-2.1 is *uncovered*.
**Maintainer**: Then no test carries its suffix. Either the test lacks `__spec_00003_AC_2_1` or the AC was added after the plan closed.
**Dev**: The AC was rewritten in a revision round last week, not added.
**Maintainer**: Then the record row should be *suspect*, not uncovered — check whether the row carries an `ac:<hash>`. If not, the record predates hashes; write a new record, records are immutable.
**Dev**: And the FR itself, does it need a test?
**Maintainer**: No. A requirement item is verified through its ACs. An item with no AC is *unverified*, which fails the spec, not the plan.

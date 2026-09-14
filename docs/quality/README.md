# Quality Requirements

Quality requirements: how well the system does something, each with a measure.
`QUALITY.md` is the method; this folder holds the instances. Front matter:
`TEMPLATE.md`. Flow position: `QUALITY.md`, Where It Sits in the Flow.

## Must Include

- context: the artifacts covered, and the ranked `prd` Quality Goals refined
- the profile: one value per `QUALITY.md` Profile dimension, each citing the
  `idea` / `prd` passage or the `decision` that chose it; neither = open
  question. Precedes the requirements because it decides which are owed. The
  audit checks every dimension is valued and sourced; the script checks only
  `n/a` cells (no-runtime exception, `QUALITY_PROFILE.md` Rules): each cites an
  `active` `decision` motivated by this doc, none sits on a mandatory
  dimension, no `runtime` `QS` exists in the repo, every catalogue row is present
- quality requirements `quality-<n>-QR-<i>`, each with exactly one tag from the
  `QUALITY.md` Attribute Axis
- quality scenarios `quality-<n>-QS-<i>.<k>`, one or more per `QR`, six-part
  form with method and stage annotation; a `QR` no scenario references is
  unverified
- a verification plan: per scenario, the tool or guide that runs it, the test
  that proves it when `build`-stage, where its evidence lands. Prose; the
  audit reads it
- open questions; a scenario whose Measure has no number is an open question,
  not a scenario (the script checks the `Measure:` label, not the number)

Add more when useful.

## Machine-readable form (item grammar)

A line that does not fit is a parse error:

- Requirement declaration, whole line, list form only:
  `- **quality-<n>-QR-<i>** (<Tag>) <text>`; indented lines that follow are
  continuation. No decision-table form: a table row starting with a bold item
  id is a parse error. `<Tag>` is one of `Efficient`, `Reliable`, `Secure`,
  `Maintainable`, `Operable`, `Flexible`, `Usable`, `Safe` (`QUALITY.md`
  Attribute Axis; the script carries the same list).
- Scenario declaration starts
  `- **quality-<n>-QS-<i>.<k>** (quality-<n>-QR-<i>) [<method> | <stage>]`;
  attribution and annotation both required. `<method>` ∈ `fitness`, `test`,
  `load`, `chaos`, `observe`; `<stage>` ∈ `build`, `release`, `runtime`. The
  six parts follow as continuation lines starting `Source:`, `Stimulus:`,
  `Artifact:`, `Environment:`, `Response:`, `Measure:`; a missing label is a
  parse error (presence checked, not order).
- Only declarations prefixed with **this document's id** belong to it; a
  whole-line reference to another doc's item is not a declaration.
- In prose, item ids are in backticks; **a bold id is the declaration form
  only**.

## Relations

- `parent` — the `prd` whose Quality Goals this doc refines; empty when the
  quality doc is the entry point.
- `informs` — the `spec` / `design` / `plan` docs these requirements feed. A
  `spec` cites the scenarios it must hold in its §7 Quality table.
- Downstream docs point here: a `design` declares `implements: [<QS ids>]`; an
  `operation` doc carrying a `runtime` scenario declares
  `implements: [<this doc or its QS ids>]`; a `plan` puts `QR` / `QS` ids in
  `implements` for delivery scope; a `record` lists `QS` ids in `verifies`.

## Enforcement

- `enforced_by` — tests that fail when a `build` scenario is violated; same
  semantics as on a `decision`, not a relation. Required when any `QS` is
  `build`-stage; checked once the doc is `active`, every path must exist.
  Doc-level: the test-to-scenario mapping is §5 Verification Plan.
- A `runtime` scenario is verified by an `operation` doc that `implements` it.
  Missing while the quality doc is `active`: warning (the operation doc is
  written in the `spec` stage). Missing once a `plan` carrying the scenario is
  `resolved`: error.

## Exclude

- what the system does (`spec/`)
- business rules (`rule/`)
- the raw facts the profile is derived from — actor counts, usage frequency,
  data kinds (`prd` Scale and Context; the profile cites it)
- the tactic that achieves a measure — cache, replica, queue, retry (`design/`)
- SLI query, alert rule, dashboard, runbook (`operation/`)
- load or chaos tooling and commands (`PERFORMANCE_TESTING.md` /
  `RESILIENCE_TESTING.md`)
- test reports and measurements (`record/`)

## Note

`QR` says how well; Measure says how it is judged; `QS` says under what
stimulus and environment the Measure holds. A number without environment and
duration is unfalsifiable. Every `QR` is a claim someone will be paged for or
pay for; if nobody would, it does not belong here.

## Sizing and Splitting

One quality doc = one artifact scope (a system, a bounded context, a shared
platform) or one attribute cluster across the system. Review triggers, not
gates: mixed scopes with different owners (web-tier latency and batch-job
cost); an auditor cannot read it in one pass (~15 QRs). One workable split
for a service, not a default: baseline (Efficient, Reliable, Operable),
security (Secure, Safe), maintainability (Maintainable, Flexible; mostly
`fitness` at `build`). Split per `docs/README.md` (`supersedes` + `archived`);
item ids are namespaced by doc id, never renumbered.

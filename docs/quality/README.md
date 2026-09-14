# Quality Requirements

This directory stores quality requirements — the non-functional requirements:
how well the system does something, each with a measure. `QUALITY.md` at the
repo root is the method; this folder holds the instances.
Use `TEMPLATE.md` for front matter.

Where this stage sits, and who waits on it: `QUALITY.md`, Where It Sits in the
Flow.

## Must Include

- context: the artifacts the requirements cover, and the ranked Quality Goals
  from the `prd` they refine
- quality requirements, numbered `quality-<n>-QR-<i>`, each tagged with exactly
  one attribute from the `QUALITY.md` Attribute Axis
- quality scenarios, numbered `quality-<n>-QS-<i>.<k>`, one or more per `QR`,
  each in the six-part form with its method and stage annotation
- acceptance for every requirement: each `quality-<n>-QR-<i>` needs at least
  one `quality-<n>-QS-<i>.<k>`; a `QR` no scenario references is unverified
- a verification plan: per scenario, the tool or guide that runs it, the test
  that proves it when it is `build`-stage, and where its evidence lands. Prose
  the script does not parse; the audit reads it
- open questions — what is still undecided; a scenario whose Measure has no
  number yet is an open question, not a scenario. The script checks the
  `Measure:` label is present, not that a number follows

Add more when useful.

## Machine-readable form (item grammar)

Documents in this folder are parsed in the form below; a line that does not fit
is a parse error:

- A requirement declaration is a whole line in list form only:
  `- **quality-<n>-QR-<i>** (<Tag>) <text>`; indented lines that follow are
  continuation lines. There is no decision-table form: a table row starting
  with a bold item id is a parse error. `<Tag>` is required and must be one of
  the eight tags in `QUALITY.md` Attribute Axis (`Efficient`, `Reliable`,
  `Secure`, `Maintainable`, `Operable`, `Flexible`, `Usable`, `Safe`); the
  script carries the same list.
- A scenario declaration starts
  `- **quality-<n>-QS-<i>.<k>** (quality-<n>-QR-<i>) [<method> | <stage>]`;
  the attribution and the annotation are both required. `<method>` is one of
  `fitness`, `test`, `load`, `chaos`, `observe`; `<stage>` is one of `build`,
  `release`, `runtime`. The six parts follow as continuation lines, each
  starting with its label: `Source:`, `Stimulus:`, `Artifact:`,
  `Environment:`, `Response:`, `Measure:`. A scenario missing any label is a
  parse error; the script checks presence, not order.
- Only declarations prefixed with **this document's id** belong to this
  document; a whole-line reference to another document's item is not a
  declaration and is not diagnosed.
- In prose, item ids are always in backticks; **a bold id is the declaration
  form only** — a line that starts with a bold item id and does not fit the
  forms above is a parse error.

## Relations

- `parent` — the `prd` whose Quality Goals this doc refines, or empty when the
  quality doc is itself the entry point.
- `informs` — the `spec` / `design` / `plan` docs these requirements are input
  for. A feature `spec` cites the scenarios it must hold in its §7 Quality
  table.
- Downstream docs point here: a `design` that realises a scenario declares
  `implements: [<QS ids>]`; an `operation` doc that carries a `runtime`
  scenario declares `implements: [<this doc or its QS ids>]`; a `plan` puts
  `QR` / `QS` ids in its `implements` to take them into delivery scope; a
  `record` lists `QS` ids in `verifies`.

## Enforcement

- `enforced_by` — the tests that fail when a `build`-stage scenario is
  violated; same semantics as on a `decision`, not a relation. Required when
  any `QS` is at the `build` stage; the script checks it once the doc is
  `active`, and that every path exists. Doc-level, as on a `decision`: the
  test-to-scenario mapping is the §4 Verification Plan.
- A `runtime`-stage scenario is verified by an `operation` doc that
  `implements` it. Missing while the quality doc is `active` is a warning
  (the operation doc is written in the `spec` stage, after `quality` turns
  `active`); missing once a `plan` carrying the scenario is `resolved` is an
  error.

## Exclude

- what the system does (use `spec/`)
- business rules (use `rule/`)
- the tactic that achieves a measure — cache, replica, queue, retry (use `design/`)
- the SLI query, alert rule, dashboard, or runbook (use `operation/`)
- load or chaos tooling and commands (use `PERFORMANCE_TESTING.md` /
  `RESILIENCE_TESTING.md`)
- test reports and measurements (use `record/`)

## Note

A `QR` says how well; the Measure says how that is judged; the `QS` says under
what stimulus and environment the Measure must hold. A number stated without
its environment and duration is unfalsifiable.

Every `QR` is a claim someone will be paged for or will pay for. If nobody
would, it does not belong here.

## Sizing and Splitting

One quality doc is one artifact scope — a system, a bounded context, a shared
platform — or one attribute cluster across the system. Review triggers, not
hard gates:

- the doc mixes scopes with different owners (the web tier's latency and the
  batch job's cost)
- an auditor can no longer read it in one pass (as an order of magnitude: past
  ~15 QRs)

One workable split for a service — not a default: a system baseline
(Efficient, Reliable, Operable), a security doc (Secure, Safe), and a
maintainability doc (Maintainable, Flexible) whose scenarios are mostly
`fitness` at `build`. Split the doc per `docs/README.md` (`supersedes` +
`archived`), like an oversized spec; item ids are namespaced by doc id and are
never renumbered.

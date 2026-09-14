# Quality

## Purpose

This file defines how a **quality requirement** — a non-functional requirement:
how *well* the system does something, with a measure — is classified, written,
verified, and kept true after release.

Use it to decide:
- where quality requirements sit in the product flow, and what waits on them
- which profile the system has — its scale, workload shape, consistency,
  availability, sensitivity, operations — and what that profile obliges
- whether a statement is a quality requirement or a system requirement
- which quality attribute a requirement belongs to
- how to write the scenario that makes it checkable
- which verification method and stage it needs, and what evidence closes it

This file is the entry point; the two companion files are read only when the
task needs them. Upstream guides (`AGENTS.md`, `AUTOPILOT.md`, folder READMEs)
cite this file alone.

| Task | Read |
| --- | --- |
| decide whether a statement is a `QR` or an FR; cite a scenario from a `spec`; verify a `plan`; audit a `design` | this file |
| derive a system's profile from its `idea` / `prd`; check what a profile value obliges the `architecture` stage to decide | [QUALITY_PROFILE.md](QUALITY_PROFILE.md) |
| write or audit `QR`s and `QS`s — tag meanings and typical measures, scenario pattern, minimum set, omission sweep | [QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md) |

Scope boundary: [ACCEPTANCE.md](ACCEPTANCE.md) derives the acceptance a `spec`
requirement or a business `rule` owes; [TESTING.md](TESTING.md) says at which
level a test runs. This file owns the third requirement namespace — `quality`
docs, their `QR` items and `QS` scenarios — and the verification methods that
are not a test in the `TESTING.md` sense: load, fault injection, and
observation in a running environment. [CODE_QUALITY.md](CODE_QUALITY.md) is
downstream of this file: its gates are the `fitness` verification of
maintainability requirements, at the `build` stage.

The form is the Quality Attribute Scenario (Bass, Clements, Kazman, *Software
Architecture in Practice*, 4th ed., 2021 — its six parts in its order, with
"Response Measure" shortened to "Measure"); the attribute vocabulary is
the arc42 quality model's tags (quality.arc42.org, Q42), each mapped to an
ISO/IEC 25010:2023 characteristic (the one omission is explained in `QUALITY_SCENARIOS.md`) —
the same site's example requirements and solution approaches are the first
place to look before writing a scenario or a tactic; the three verification stages are the fitness function (Ford,
Parsons, Kua, *Building Evolutionary Architectures*, 2017), the operational
readiness review (Amazon's ORR, Google's PRR), and the SLO (Beyer et al.,
*Site Reliability Engineering*, 2016).

## Where It Sits in the Flow

`docs/quality/` is a core stage: `idea -> prd -> quality -> spec`, with
architecture decisions and `design` docs waiting on `quality`.

- The `prd` ranks Quality Goals in business language, without numbers.
- The `quality` docs first derive the system's profile from the `idea` and the
  `prd` (Profile), then refine each ranked goal into `QR`s with `QS`s,
  owing at least what the profile drives. They carry the `prd` as `parent` and
  `informs` the specs, designs, and plans that must hold their scenarios — the
  same edge a `rule` declares toward the spec that applies it.
- Architecture decisions come after: a technology or structure `decision` names
  in `motivated_by` the scenarios it serves. A `design` declares `implements`
  on the scenarios it realises. Quality scenarios are the architecture drivers
  (SEI: architecturally significant requirements); choosing a stack before
  writing them is choosing blind.
- A feature `spec` cites, in its §7 Quality table, the quality docs and
  scenarios it must hold.

Every project that ships software writes at least one quality doc. A library
or a CLI with no user waiting on it still has maintainability targets — the
`CODE_QUALITY.md` gates are its `fitness` scenarios — and a system anyone
depends on has latency, availability, security, and cost targets whether or
not they are written down. Unwritten, they are still enforced: by the
incident. The one exemption is a repo with no runtime artifact at all — this
template itself — which has nothing to measure.

## The FR / QR Test

A `spec` requirement says what the system does. A quality requirement says how
well, and is judged by a **response measure** — a quantity with a unit, a
percentile, a window, or a rate — stated in its scenarios. The test: remove the
measure — if nothing is left to check, it was never a quality requirement.

- "If the provider does not respond before timeout, keep the attempt
  PROCESSING" — behaviour, no measure → `spec` FR (Unwanted).
- "Duplicate webhooks are applied at most once" — behaviour → `spec` FR.
- "p99 of `POST /payments` ≤ 300 ms at 500 rps, 30 min, production-sized
  data" — a measure → `quality` QR.
- "The system should be fast" — no measure. Not a requirement yet; an Open
  Question until someone names the number.

The behaviour a system shows when a quality target is exceeded (shed load,
degrade, reject) is itself behaviour: an Unwanted FR in the consuming `spec`,
cited from the QR. That FR and its AC name the threshold by citing the scenario
(`the load quality-00001-QS-1.1 is held at`), never by restating the number —
the number has one owner, and changing it does not reopen the spec.

The three namespaces, with their tests:

| Namespace | Owner | Test |
| --- | --- | --- |
| `rule-<n>-BR-<i>` | `rule/` | remove the software — still true? |
| `spec-<n>-FR-<i>` | `spec/` | what the system does |
| `quality-<n>-QR-<i>` | `quality/` | has a response measure |

## Profile

Before any `QR` is written, the quality doc values the system's **profile**: the
dimensions of its workload and context — scale, growth, traffic shape, read /
write mix, consistency and partition preference, availability and recovery,
latency class, data sensitivity, tenancy, operations model, team shape, cost
posture — that decide which quality requirements it owes and which
architecture decisions cannot be skipped. Each dimension takes one value from a
closed list ordered from least to most obliging, cites the `idea` / `prd`
passage it was read from or the `decision` that chose it, and drives a minimum
set of `QR`s, scenario stages, and `decision`s. The dimension catalogue, its
rules, and its sources are [QUALITY_PROFILE.md](QUALITY_PROFILE.md).

## Attribute Axis

Every `QR` carries exactly one tag from the arc42 quality model's vocabulary:
`Efficient`, `Reliable`, `Secure`, `Maintainable`, `Operable`, `Flexible`,
`Usable`, `Safe`. This file is the vocabulary's single source;
`docs/quality/README.md` and `scripts/check-docs-drift.sh` carry the same list
and change with it. What each tag asks, its ISO/IEC 25010:2023 mapping, and its
typical measures are in [QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md).

## Scenario Form

A `QR` is made checkable by one or more **quality scenarios** (`QS`), each with
six parts on their own lines: Source, Stimulus, Artifact, Environment, Response,
Measure — the Quality Attribute Scenario, with "Response Measure" shortened.
For readers who know GWT: Given = Environment + Artifact, When = Source +
Stimulus, Then = Response + Measure. What each part states, how a scenario is
written, and how many a `QR` owes are in
[QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md); the parse grammar is in
`docs/quality/README.md`.

## Verification Axis

Each `QS` declares one **method** and one **stage** in its annotation:
`[<method> | <stage>]`.

| Method | Proves | Tool class | `record` row: Test cell | `record` row: Evidence cell |
| --- | --- | --- | --- | --- |
| `fitness` | a structural property holds | architecture tests, static analysis, the `CODE_QUALITY.md` §2 gates | the test path, also listed in the quality doc's `enforced_by` | optional |
| `test` | a behaviour holds under one controlled stimulus | a test at the level `TESTING.md` assigns | the test name | optional |
| `load` | a measure holds under a stated volume and concurrency | load generator per [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md) | the load profile | **required**: a report artifact — percentiles, throughput, error rate, duration, environment |
| `chaos` | the system responds as stated when a dependency or resource fails | fault injection per [RESILIENCE_TESTING.md](RESILIENCE_TESTING.md) | the experiment | **required**: an experiment report — hypothesis, injected fault, observed response, blast radius |
| `observe` | a measure holds over a window in a real environment | SLI / SLO with alert and error budget, in `docs/operation/` | the SLO name | **required**: the operation doc and the alert rule's location |

| Stage | Runs | Gate |
| --- | --- | --- |
| `build` | every commit | a failing scenario fails the build (`CODE_QUALITY.md` Architecture gate, `TESTING.md`) |
| `release` | before the delivering `plan` turns `resolved`, and again on every release that touches the artifact | a `pass` row with evidence in the `record` |
| `runtime` | continuously, in staging or production | an `active` `operation` doc holding the SLI, the SLO (= the Measure), the alert, and the error budget policy; the `record` row shows that doc and the alert wired |

A consequence, chosen on purpose: a `plan` that takes a `load` or `chaos`
scenario into scope cannot resolve until the environment that runs it exists.
The gate is the reason the environment gets built.

Method and stage pair naturally — `fitness` and `test` at `build`, `load` and
`chaos` at `release`, `observe` at `runtime` — but the pairing is declared, not
assumed: a micro-benchmark is `load` at `build`; a game day is `chaos` at
`runtime`.

A `runtime` pass in the `record` means the SLO is defined and alerting — not
that the measure has been met over a window; a release cannot prove that, and
an unattended run cannot wait for it. Whether it is met is the dashboard's
job, and the error budget policy in the operation doc says what happens when
it is not. This is a chosen reading: a reviewer who wants a baseline before
`resolved` asks for a `release`-stage `observe` scenario with the window in its
Environment.

## Priorities and Conflicts

- The `prd` Quality Goals section ranks the attributes that matter, top first,
  in business language and without numbers; `ARCHITECTURE.md` §1 mirrors the
  ranking, §10 indexes the quality docs. Numbers live in `QS` Measures only.
- A `design` that realises a scenario declares `implements: [<QS ids>]`. Its §2
  Trade-offs names the scenarios it trades against each other.
- When two `QR`s conflict, or a tactic buys one attribute with another, the
  choice is a `decision` with `motivated_by` naming both ids.
- The `AGENTS.md` §6 audit of a `design` includes a sensitivity check: each
  tactic against the scenario list — which `QS` it serves, which it costs.

`enforced_by` is declared at doc level, as on a `decision`; which test proves
which `build` scenario is written in the doc's §5 Verification Plan, which no
script parses. The script proves the field is present and its paths exist; the
audit proves the mapping.

## Definition of Done

A quality set is done when:

- every Profile dimension (`QUALITY_PROFILE.md`) has one value, each citing its source or its `decision`
- everything the Profile drives exists — the `QR`s, the scenario stages, the `decision`s the `architecture` stage owes
- every `QR` carries one tag from the vocabulary and at least one `QS`
- every `QS` declares method and stage, and its Measure carries a number, a
  unit, and the environment and duration it holds under
- every `build` scenario's test is listed in `enforced_by` and passes
- every `release` scenario has a `pass` row with a report artifact as evidence
- every `runtime` scenario has an `active` `operation` doc that `implements`
  it, and a `record` row showing that doc and the alert wired
- the omission heuristics (`QUALITY_SCENARIOS.md`) were swept, and what they
  raised became a scenario, a `decision`, or an Open Question
- every ranked Quality Goal in the `prd` is refined by at least one `QR`
- no `decision` in reach chose a technology or a structure without naming the
  scenarios it serves

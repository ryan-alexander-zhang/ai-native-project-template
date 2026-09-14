# Quality

## Purpose

Owns the third requirement namespace: `quality` docs, their `QR` items — how
*well* the system does something, with a measure — and `QS` scenarios, plus the
verification methods that are not a test in the `TESTING.md` sense: load, fault
injection, observation in a running environment.

Boundaries: [ACCEPTANCE.md](ACCEPTANCE.md) derives what a `spec` or `rule` owes;
[TESTING.md](TESTING.md) assigns test levels; [CODE_QUALITY.md](CODE_QUALITY.md)
is downstream — its gates are the `fitness` verification of maintainability
`QR`s at `build`.

Entry point. Upstream guides cite this file only; read a companion when the task
needs it.

| Task | Read |
| --- | --- |
| decide `QR` or FR; cite a `QS` from a `spec`; verify a `plan`; audit a `design` | this file |
| derive a profile from the `idea` / `prd`; what a profile value obliges the `architecture` stage to decide | [QUALITY_PROFILE.md](QUALITY_PROFILE.md) |
| write or audit `QR`s and `QS`s — tag meanings, typical measures, scenario pattern, minimum set, omission sweep | [QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md) |

## Where It Sits in the Flow

`idea -> prd -> quality -> spec`; architecture `decision`s and `design` docs wait
on `quality`. Quality scenarios are the architecture drivers; choosing a stack
before writing them is choosing blind.

- `prd` ranks Quality Goals in business language, no numbers. `ARCHITECTURE.md`
  §1 mirrors the ranking, §10 indexes the quality docs. Numbers live in `QS`
  Measures only.
- `quality` doc: `parent` = the `prd`. Derives the Profile first, then refines
  each ranked goal into `QR`s with `QS`s, owing at least what the profile
  drives. `informs` the specs, designs, and plans that must hold its scenarios.
- A technology or structure `decision` names the `QS`s it serves in
  `motivated_by`. A `design` declares `implements: [<QS ids>]`; its §2
  Trade-offs names the scenarios it trades. Two `QR`s in conflict, or a tactic
  that buys one attribute with another: a `decision` with `motivated_by` naming
  both ids.
- A `spec` cites the quality docs and `QS`s it must hold in its §7 Quality table.
- Every repo with a runtime artifact writes at least one quality doc — a
  library's `CODE_QUALITY.md` gates are its `fitness` scenarios; unwritten
  targets are enforced by the incident. Exempt: a repo with nothing to run
  (this template).

## The FR / QR Test

Remove the measure. Nothing left to check → not a `QR`.

- "provider does not respond before timeout → attempt stays PROCESSING":
  behaviour → `spec` FR (Unwanted).
- "p99 of `POST /payments` ≤ 300 ms at 500 rps, 30 min, production-sized data":
  measure → `QR`.
- "should be fast": no measure → Open Question until someone names the number.

Behaviour when a target is exceeded (shed, degrade, reject) is an Unwanted FR in
the consuming `spec`. Its AC cites the `QS` id (`the load quality-00001-QS-1.1
is held at`), never restates the number: one owner per number.

| Namespace | Owner | Test |
| --- | --- | --- |
| `rule-<n>-BR-<i>` | `rule/` | still true without the software |
| `spec-<n>-FR-<i>` | `spec/` | what the system does |
| `quality-<n>-QR-<i>` | `quality/` | has a response measure |

## Profile

Before any `QR`: one value per profile dimension — scale, growth, traffic
shape, read / write mix, consistency and partition preference, availability and
recovery, latency class, sensitivity, tenancy, operations model, team shape,
cost posture — from a closed list ordered least → most obliging, citing the
`idea` / `prd` passage or the `decision` that chose it. Each value drives a
minimum set of `QR`s, stages, and `decision`s. Catalogue, rules, sources:
[QUALITY_PROFILE.md](QUALITY_PROFILE.md).

## Attribute Axis

One tag per `QR`: `Efficient`, `Reliable`, `Secure`, `Maintainable`,
`Operable`, `Flexible`, `Usable`, `Safe` (arc42 Q42). This list is the single
source; `docs/quality/README.md` and `scripts/check-docs-drift.sh` copy it.
Meanings, ISO/IEC 25010:2023 mapping, typical measures:
[QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md).

## Scenario Form

A `QS` has six parts on their own lines: Source, Stimulus, Artifact,
Environment, Response, Measure. GWT: Given = Environment + Artifact, When =
Source + Stimulus, Then = Response + Measure. Parts, pattern, minimum set:
[QUALITY_SCENARIOS.md](QUALITY_SCENARIOS.md); parse grammar:
`docs/quality/README.md`.

## Verification Axis

Each `QS` declares `[<method> | <stage>]`.

| Method | Proves | Tool class | `record` row: Test cell | `record` row: Evidence cell |
| --- | --- | --- | --- | --- |
| `fitness` | a structural property holds | architecture tests, static analysis, the `CODE_QUALITY.md` §2 gates | the test path, also in the quality doc's `enforced_by` | optional |
| `test` | a behaviour holds under one controlled stimulus | a test at the level `TESTING.md` assigns | the test name | optional |
| `load` | a measure holds under a stated volume and concurrency | load generator per [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md) | the load profile | **required**: report — percentiles, throughput, error rate, duration, environment |
| `chaos` | the system responds as stated when a dependency or resource fails | fault injection per [RESILIENCE_TESTING.md](RESILIENCE_TESTING.md) | the experiment | **required**: report — hypothesis, injected fault, observed response, blast radius |
| `observe` | a measure holds over a window in a real environment | SLI / SLO with alert and error budget, in `docs/operation/` | the SLO name | **required**: the operation doc and the alert rule's location |

| Stage | Runs | Gate |
| --- | --- | --- |
| `build` | every commit | a failing scenario fails the build |
| `release` | before the delivering `plan` turns `resolved`, and on every release that touches the artifact | a `pass` row with evidence in the `record` |
| `runtime` | continuously, in staging or production | an `active` `operation` doc holding SLI, SLO (= the Measure), alert, error budget policy; the `record` row shows that doc and the alert wired |

- Pairing is declared, not assumed: a micro-benchmark is `load | build`; a game
  day is `chaos | runtime`.
- A `plan` with a `load` / `chaos` `QS` in scope cannot resolve until the
  environment that runs it exists. The gate is why the environment gets built.
- A `runtime` `pass` means SLO defined and alerting — not met over a window;
  the dashboard and the error budget policy own that. A baseline before
  `resolved` is a `release`-stage `observe` `QS` with the window in its
  Environment.
- `enforced_by` is doc-level, as on a `decision`; §5 Verification Plan maps
  test → `build` `QS`. The script checks presence and paths; the audit checks
  the mapping.
- The `AGENTS.md` §6 audit of a `design` includes a sensitivity check: each
  tactic against the `QS` list — which it serves, which it costs.

## Definition of Done

- every Profile dimension has one value citing its source or `decision`
  (`n/a` only under the no-runtime exception, `QUALITY_PROFILE.md` Rules)
- everything the Profile drives exists: `QR`s, scenario stages, the
  `decision`s the `architecture` stage owes
- every ranked `prd` Quality Goal is refined by at least one `QR`
- every `QR` has one tag and at least one `QS`
- every `QS` declares method and stage; its Measure has a number, a unit, and
  the environment and duration it holds under
- every `build` `QS`: test listed in `enforced_by`, passing
- every `release` `QS`: a `pass` row with a report artifact
- every `runtime` `QS`: an `active` `operation` doc that `implements` it, and a
  `record` row showing that doc and the alert wired
- the omission heuristics (`QUALITY_SCENARIOS.md`) were swept; what they raised
  is a `QS`, a `decision`, or an Open Question
- no `decision` in reach chose a technology or structure without naming the
  `QS`s it serves

Sources: Bass, Clements, Kazman, *Software Architecture in Practice* 4e
(scenario form); quality.arc42.org (tags, example requirements, approaches);
Ford, Parsons, Kua, *Building Evolutionary Architectures* (fitness); Amazon
ORR / Google PRR (release); Beyer et al., *Site Reliability Engineering* (SLO).

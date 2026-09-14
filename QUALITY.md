# Quality

## Purpose

This file defines how a **quality requirement** — a non-functional requirement:
how *well* the system does something, with a measure — is classified, written,
verified, and kept true after release.

Use it to decide:
- where quality requirements sit in the product flow, and what waits on them
- whether a statement is a quality requirement or a system requirement
- which quality attribute a requirement belongs to
- how to write the scenario that makes it checkable
- which verification method and stage it needs, and what evidence closes it

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
ISO/IEC 25010:2023 characteristic (see Attribute Axis for the one omission); the three verification stages are the fitness function (Ford,
Parsons, Kua, *Building Evolutionary Architectures*, 2017), the operational
readiness review (Amazon's ORR, Google's PRR), and the SLO (Beyer et al.,
*Site Reliability Engineering*, 2016).

## Where It Sits in the Flow

`docs/quality/` is a core stage: `idea -> prd -> quality -> spec`, with
architecture decisions and `design` docs waiting on `quality`.

- The `prd` ranks Quality Goals in business language, without numbers.
- The `quality` docs refine each ranked goal into `QR`s with `QS`s. They carry
  the `prd` as `parent` and `informs` the specs, designs, and plans that must
  hold their scenarios — the same edge a `rule` declares toward the spec that
  applies it.
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

## Attribute Axis

Every `QR` carries exactly one tag from this vocabulary. Sub-characteristics
(time behaviour, recoverability, …) go in the requirement text, not in the tag.
This file is the vocabulary's single source; `docs/quality/README.md` and
`scripts/check-docs-drift.sh` carry the same list and change with it. Both are
template-owned, so a project that needs a tag names the nearest one, puts the
sub-characteristic in the text, and proposes the tag upstream with a
`decision`; it does not fork the list.

| Tag | Asks | ISO/IEC 25010:2023 | Typical measure |
| --- | --- | --- | --- |
| Efficient | how fast, how much, with what resources — and at what cost | Performance efficiency (time behaviour, capacity, resource utilization) | latency percentile at stated load; throughput; CPU / memory per unit of work; cost per request / tenant / month |
| Reliable | keeps working, recovers, tolerates faults | Reliability | availability over a window; MTTR; RPO / RTO; error rate |
| Secure | resists and detects misuse | Security | time to detect; time to revoke; attack surface count; audit completeness |
| Maintainable | can be changed safely | Maintainability | change lead time; dependency direction and cycle count; complexity gates |
| Operable | can be run, observed, deployed, and recovered by its operators | no single characteristic; arc42 addition, drawing on Maintainability (analysability) and Flexibility (installability) | time to detect from telemetry; deploy lead time and rollback time; alert precision; runbook coverage |
| Flexible | adapts, scales, installs, coexists, interoperates, replaces | Flexibility (2011: portability, plus scalability) and Compatibility | scale-out time; horizontal scaling factor; install time; contract conformance; zero-downtime upgrade |
| Usable | can be used by its users | Interaction capability (2011: usability) | task completion time; error rate per task; accessibility conformance |
| Safe | does not harm people, property, environment | Safety | hazard rate; fail-safe transition time |

Why arc42's tags: `ARCHITECTURE.md` is arc42-shaped, so §1 and §10 read in the
same words; one-word tags are short in the `(Tag)` annotation; and `Operable`
names what the `runtime` stage depends on — observability, deployability,
recoverability — which ISO scatters across sub-characteristics. One deliberate
omission from Q42: **Suitable** (ISO Functional suitability) — what the system
does, and whether it does it completely and correctly, is the `spec`
namespace. This is the one exception to the FR / QR test: a measured
functional quality (accuracy, precision, recall, coverage of a benchmark set)
stays a `spec` FR, and its AC states the threshold and the data set it holds
on. Cost has no tag of its own: it is resource utilization times a
price, so it is measured under `Efficient`.

## Scenario Form

A `QR` is made checkable by one or more **quality scenarios** (`QS`). Each
scenario has six parts, each on its own line:

| Part | States |
| --- | --- |
| Source | who or what produces the stimulus — a user class, another system, a fault, an operator |
| Stimulus | what arrives — a request volume, a dependency failure, a change request, an attack |
| Artifact | the part of the system stimulated — an endpoint, a service, a module, a data store |
| Environment | the state the system is in — normal load, peak, degraded, during deploy, with production-sized data |
| Response | what the system does |
| Measure | how the response is judged — a number with unit; a percentile for latency; a window for rates and availability; the duration and volume the measurement ran under |

For readers who know GWT: Given = Environment + Artifact, When = Source +
Stimulus, Then = Response + Measure. The split is finer because the parts are
what a load profile, a fault injection, and an SLI definition are built from.

## Scenario Pattern

- One stimulus per scenario — two faults, or a load and a fault, are two scenarios.
- Measure carries a number, a unit, and the window or duration it holds over;
  a percentile for latency, a sample size for load. A Measure without a number
  is an Open Question, not a scenario (the script checks the label is present,
  not that the number is).
- Environment names what may not be substituted — data size, dependency
  latency, cluster size. A Measure with no Environment is unfalsifiable.
- Response is what the system does, observable from outside; the tactic that
  achieves it belongs in a `design`.
- A scenario that restates its `QR` with a number attached verifies nothing
  more than the `QR`; it must add the stimulus and environment under which the
  number holds.
- The six parts are written in the order above; the script checks each label
  is present, not the order.

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

## Minimum Set — per QR

The floor a `QR` owes, by what its measure depends on. Rows are policy, not
derivation: a project that wants a lower floor changes this table, not the doc.

| The QR's measure … | Minimum scenarios |
| --- | --- |
| any QR | one `QS` |
| can change with data volume, traffic, or infrastructure (latency, throughput, availability, cost) | one `release` and one `runtime` scenario, so it is proven before ship and watched after |
| is a structural property (dependency direction, module boundary, size, banned API) | one `fitness` scenario at `build`; cite the `CODE_QUALITY.md` gate rather than restate it |
| concerns a dependency the artifact cannot work without | one `chaos` scenario per such dependency: it fails, the stated response happens within the stated time |
| concerns misuse (Secure, Safe) | one scenario per stimulus, naming detection time and containment response; the stimuli are drawn from the Security Areas in `SECURITY.md` (secrets, auth and access, data handling, dependencies, API surface) and the project's threat model when it has one |
| is exceeded — load past the stated volume, fault past the stated tolerance | the degradation behaviour is an Unwanted FR in the consuming `spec`; the QR cites it |

## Omission Heuristics

The tables above complete the set against what is written. Sweep these before
closing:

- **goal without QR** — a ranked Quality Goal in the `prd` that no `QR` refines
- **QR without a runtime scenario** whose measure a real environment can move
- **dependency without a chaos scenario** — a store, broker, or third party the artifact needs
- **measure without an environment** — a number stated with no load, data size, or duration
- **tactic without a scenario** — a `design` that adds a cache, a queue, a replica, a retry, and cites no `QS` it serves
- **conflict without a decision** — two `QR`s pulling opposite ways, or a `design` §2 trade-off between attributes, with no `decision` naming both

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
which `build` scenario is written in the doc's §4 Verification Plan, which no
script parses. The script proves the field is present and its paths exist; the
audit proves the mapping.

## Definition of Done

A quality set is done when:

- every `QR` carries one tag from the vocabulary and at least one `QS`
- every `QS` declares method and stage, and its Measure carries a number, a
  unit, and the environment and duration it holds under
- every `build` scenario's test is listed in `enforced_by` and passes
- every `release` scenario has a `pass` row with a report artifact as evidence
- every `runtime` scenario has an `active` `operation` doc that `implements`
  it, and a `record` row showing that doc and the alert wired
- the omission heuristics were swept, and what they raised became a scenario,
  a `decision`, or an Open Question
- every ranked Quality Goal in the `prd` is refined by at least one `QR`
- no `decision` in reach chose a technology or a structure without naming the
  scenarios it serves

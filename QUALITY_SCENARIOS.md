# Quality Scenarios

Companion to [QUALITY.md](QUALITY.md), read when `QR`s and `QS`s are written or
audited: what each tag asks and how it is typically measured, how a scenario is
written, how many a `QR` owes, and what to sweep for before closing the set.
The rules of the method stay in `QUALITY.md`.

## Attribute Axis

Every `QR` carries exactly one tag from the vocabulary `QUALITY.md` names.
Sub-characteristics (time behaviour, recoverability, …) go in the requirement
text, not in the tag. The list is template-owned, so a project that needs a tag
names the nearest one, puts the sub-characteristic in the text, and proposes
the tag upstream with a `decision`; it does not fork the list.

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

Each scenario has six parts, each on its own line:

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

## Minimum Set — per QR

The floor a `QR` owes, by what its measure depends on; the Profile's Drives
column (`QUALITY_PROFILE.md`) is the floor the system owes before any `QR` is written, and the two
compose. Rows are policy, not derivation: a project that wants a lower floor
changes this table, not the doc.

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

- **dimension without source** — a Profile value that cites no `idea` / `prd` passage and no `decision`
- **drive without doc** — a Profile value whose Drives column names a `QR`, a scenario, or a `decision` that does not exist
- **goal without QR** — a ranked Quality Goal in the `prd` that no `QR` refines
- **QR without a runtime scenario** whose measure a real environment can move
- **dependency without a chaos scenario** — a store, broker, or third party the artifact needs
- **measure without an environment** — a number stated with no load, data size, or duration
- **tactic without a scenario** — a `design` that adds a cache, a queue, a replica, a retry, and cites no `QS` it serves
- **conflict without a decision** — two `QR`s pulling opposite ways, or a `design` §2 trade-off between attributes, with no `decision` naming both

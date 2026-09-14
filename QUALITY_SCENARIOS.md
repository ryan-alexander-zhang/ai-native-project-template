# Quality Scenarios

Companion to [QUALITY.md](QUALITY.md), read when `QR`s and `QS`s are written or
audited: tags and typical measures, scenario form, minimum set, omission sweep.

## Attribute Axis

One tag per `QR`, from the list `QUALITY.md` owns. Sub-characteristics (time
behaviour, recoverability, …) go in the requirement text. A project needing
another tag uses the nearest one and proposes the tag upstream in a `decision`;
it does not fork the list.

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

Q42's **Suitable** (ISO Functional suitability) is omitted: what the system does
is the `spec` namespace. The one exception to the FR / QR test follows: a
measured functional quality (accuracy, precision, recall, benchmark coverage)
stays a `spec` FR; its AC states threshold and data set. Cost has no tag: it is
resource utilization × price, measured under `Efficient`.

## Scenario Form

Six parts, each on its own line:

| Part | States |
| --- | --- |
| Source | who or what produces the stimulus — a user class, another system, a fault, an operator |
| Stimulus | what arrives — a request volume, a dependency failure, a change request, an attack |
| Artifact | the part of the system stimulated — an endpoint, a service, a module, a data store |
| Environment | the state the system is in — normal load, peak, degraded, during deploy, with production-sized data |
| Response | what the system does |
| Measure | how the response is judged — a number with unit; a percentile for latency; a window for rates and availability; the duration and volume the measurement ran under |

GWT: Given = Environment + Artifact, When = Source + Stimulus, Then = Response +
Measure. The finer split is what a load profile, a fault injection, and an SLI
are built from.

## Scenario Pattern

- One stimulus per scenario; two faults, or a load and a fault, are two scenarios.
- Measure: number, unit, window or duration; percentile for latency, sample
  size for load. No number = Open Question, not a scenario.
- Environment names what may not be substituted: data size, dependency latency,
  cluster size. No Environment = unfalsifiable.
- Response is observable from outside; the tactic belongs in a `design`.
- A `QS` that restates its `QR` with a number verifies nothing; it must add the
  stimulus and environment the number holds under.
- Parts in the order above. The script checks labels are present, not order or
  numbers.

## Minimum Set — per QR

The floor a `QR` owes; composes with the Profile's Drives column
(`QUALITY_PROFILE.md`). Policy, not derivation: to owe less, change this table,
not the doc.

| The QR's measure … | Minimum scenarios |
| --- | --- |
| any QR | one `QS` |
| can change with data volume, traffic, or infrastructure (latency, throughput, availability, cost) | one `release` and one `runtime` scenario |
| is a structural property (dependency direction, module boundary, size, banned API) | one `fitness` scenario at `build`; cite the `CODE_QUALITY.md` gate rather than restate it |
| concerns a dependency the artifact cannot work without | one `chaos` scenario per such dependency: it fails, the stated response happens within the stated time |
| concerns misuse (Secure, Safe) | one scenario per stimulus, naming detection time and containment response; stimuli from the `SECURITY.md` Security Areas and the project's threat model |
| is exceeded — load past the stated volume, fault past the stated tolerance | the degradation behaviour is an Unwanted FR in the consuming `spec`; the QR cites it |

## Omission Heuristics

Sweep before closing the set:

- **dimension without source** — a Profile value that cites no `idea` / `prd` passage and no `decision`
- **drive without doc** — a Profile value whose Drives column names a `QR`, a scenario, or a `decision` that does not exist
- **goal without QR** — a ranked Quality Goal in the `prd` that no `QR` refines
- **QR without a runtime scenario** whose measure a real environment can move
- **dependency without a chaos scenario** — a store, broker, or third party the artifact needs
- **measure without an environment** — a number stated with no load, data size, or duration
- **tactic without a scenario** — a `design` that adds a cache, a queue, a replica, a retry, and cites no `QS` it serves
- **conflict without a decision** — two `QR`s pulling opposite ways, or a `design` §2 trade-off between attributes, with no `decision` naming both

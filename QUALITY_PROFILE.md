# Quality Profile

Companion to [QUALITY.md](QUALITY.md): the dimension catalogue, read when a
profile is derived (`quality` stage) or consulted (`architecture` stage). Flow,
FR / QR test, tags, stages, Definition of Done stay in `QUALITY.md`.

## Rules

- One value per dimension, from its list; lists run least → most obliging.
- Derived, not invented: cite the `idea` / `prd` passage (Problem Statement,
  Actors, Scope, Scale and Context, Risks, Constraints). No source = a choice =
  a `decision`.
- Unvaluable dimension = Open Question; doc stays `draft` until settled or
  decided. Autopilot takes the first value and records all defaults in one
  `decision` (`AUTOPILOT.md`, stage `quality`).
- Orders of magnitude and classes only; numbers live in `QS` Measures.
- **Drives** = the minimum a value obliges (a `QR`, a stage, a `decision`). It is
  policy: to owe less, change this file, not the quality doc.
- Each Drives entry of a chosen value appears in the consuming quality doc's
  Drives here cell, carrying its state (`docs/quality/README.md`). Drives stays
  the policy; the cell is only the ledger of what the chosen value owes and
  whether it is paid.
- Scope = the quality doc's §1. A deviating feature (a batch endpoint in an
  interactive system) names the deviating dimension in its own `QR`.
- Changing a value is a revision round; every `decision` whose `motivated_by`
  reaches the doc is re-read.
- **No-runtime exception** — the only case a cell may read `n/a`, and only
  while every condition holds; one fails, the exception is void and the doc is
  in revision:
  1. This repo deploys, schedules, and serves nothing: the artifact is a
     library, SDK, or build-time tool consumed as a dependency. `ARCHITECTURE.md`
     §7 states it. An example or scaffold service that is not deployed does not
     void this; a service this repo runs anywhere does.
  2. No `quality` doc in the repo declares a `runtime` `QS`, and no `operation`
     doc `implements` one. The script checks this.
  3. An `active` `decision` records the exception, `motivated_by` this quality
     doc, §1 naming the artifact and the consuming systems that own the `n/a`
     dimensions. Every `n/a` cell cites that decision id; the script checks the
     id exists, is `active`, and is motivated by this doc.
  4. `maturity`, `team-shape`, `integration-surface`, `portability`, `harm` are
     always valued from a source; `n/a` there is an error.
  5. Every catalogue dimension still has its row. A missing row is an error, not
     an implied `n/a`.
  6. Autopilot may take it only when the `idea` names the deliverable as a
     library (intake-settled); never by `decided_by: agent`.

## Catalogue

### Scale

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `scale` | prd Actors, Scale and Context: how many actors, how often | **S** — one instance carries the peak (order 10² rps, 10⁶ rows, 10⁴ daily actors) · **M** — several instances, one region (10³ rps, 10⁹ rows, 10⁶ daily actors) · **L** — beyond: partitioned data, more than one region | S: `build` scenarios may be the whole set · M: an Efficient `QR` with one `release` load and one `runtime` observe scenario; a `decision` on stateless scale-out and store sizing · L: additionally a partitioning / sharding `decision`, a multi-region `decision`, a capacity model in `design/` |
| `growth` | prd Vision and Goals, Risks: launch plan, campaigns | flat · linear · step (launches, campaigns) · exponential | step / exponential: load scenarios at planned peak × headroom, not current load; a Flexible `QR` on scale-out time |
| `horizon` | prd Vision: how long this version must hold | 12 months · 36 months | every Measure's Environment names the horizon its volume assumes |
| `data-volume` | prd Scale and Context: rows, objects, bytes | small (fits one node with room) · large (one node, indexes decide) · very large (must be partitioned) | large: an Efficient `QR` on query time at production-sized data; `release` load runs on a production-sized set · very large: partitioning `decision` |
| `retention` | prd Scope, Risks; compliance | ephemeral · years · indefinite or regulated | years+: an Efficient cost Measure on storage; a Secure `QR` on deletion; an Operable archival runbook in `operation/` |

### Traffic shape

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `traffic-shape` | prd Actors, Scale and Context: when actors act | steady · diurnal · bursty (flash, campaign) · batch-window | bursty: load scenarios with a ramp; the degradation past peak as an Unwanted FR in the `spec` (shed, queue, reject); a Flexible scale-out `QR` · batch-window: an Efficient `QR` on window completion time |
| `read-write` | prd Functional Requirements: what actors mostly do | read-heavy · balanced · write-heavy · append-only | read-heavy: a `decision` on caching / read replicas, with staleness bound as a Measure · write-heavy: a write-path throughput `QS` and a contention `QS` · append-only: a retention / compaction `decision` |
| `access-pattern` | prd Functional Requirements | point lookup · range and list · aggregate and analytics · search · stream | analytics: a read-model or separate-store `decision` · search: an index technology `decision` · stream: a streaming platform `decision` |
| `payload` | prd Functional Requirements: what is stored and moved | small records · large objects (files, media) · mixed | large objects: an object-storage `decision`; Efficient Measures on transfer time and size limits |
| `skew` | prd Actors: celebrity entities, dominant tenants | uniform · hot keys · tenant skew | hot keys: a `QS` under skewed load · tenant skew: per-tenant limits as FRs; a Reliable isolation `QR` |

### Consistency and correctness

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `consistency` | prd Functional Requirements: what a user must see right after acting | eventual acceptable · read-your-writes · strong per aggregate · atomic across aggregates | eventual: a staleness bound as a Measure (Reliable or Usable), a read-model lag `QS` · strong: version-checked writes as FRs, a conflict-handling `QS` · across aggregates: a saga / process-manager `decision`, compensation FRs |
| `partition-preference` | prd Risks: what is worse — wrong or unavailable | availability-first (serve stale, degrade) · consistency-first (refuse) | fixes the Response of every `chaos` scenario; the degradation or refusal as an Unwanted FR in the `spec` |
| `ordering` | prd Functional Requirements: does sequence carry meaning | none · per key · global | per key: a partitioning-key `decision` for messaging; a `QS` on out-of-order delivery · global: a single-writer or sequencer `decision` |
| `delivery` | prd Functional Requirements: cost of a lost or a repeated effect | at-most-once acceptable · at-least-once with idempotent consumers · exactly-once effect | at-least-once+: outbox / inbox `decision`; idempotency FRs in the `spec`; a redelivery `QS` |
| `transaction-span` | prd Functional Requirements: what must succeed or fail together | one aggregate · several aggregates (saga) · across systems | saga+: a process-manager `decision`; replay and compensation FRs; a replay `QS` |

### Availability and recovery

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `availability` | prd Vision, Risks: what an hour of downtime costs | best effort (~99 %) · business hours · 99.9 % · 99.99 %+ | 99.9 %+: a Reliable `QR` with a `runtime` observe scenario (SLO, alert, error budget) and one `chaos` scenario per hard dependency · 99.99 %+: multi-instance / multi-zone `decision`; `deploy-tolerance` becomes zero-downtime |
| `recovery` | prd Risks: what data loss and what outage length are acceptable | hours of loss, a day to restore · minutes / an hour · seconds / minutes · zero loss | minutes+: a backup and replication `decision`; a `chaos` scenario whose Measure is the restore time; a restore runbook in `operation/` |
| `dependency-hardness` | prd Risks and Dependencies, per external dependency | soft (degrade without it) · hard (cannot work without it) | one `chaos` scenario per hard dependency; timeout and fallback FRs in the `spec` for soft ones |
| `blast-radius` | prd Actors, Scope: who may be affected by one failure | whole system acceptable · one tenant · one region | tenant / region: bulkhead and per-tenant-limit `decision`s; a `QS` proving the containment |

### Latency and interaction

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `latency-class` | prd Actors, User Experience: who waits, and for how long | batch (hours) · background (minutes) · near real time (seconds) · interactive (sub-second at p99) · real time (tens of ms) | interactive+: an Efficient `QR` with a `release` load scenario at peak and a `runtime` observe scenario; a sync-vs-async `decision` for anything slower than the class |
| `interaction` | prd Functional Requirements, User Experience | request-response · long-running (job, progress) · event-driven / streaming | long-running: progress and completion FRs; an Efficient `QR` on job latency · event-driven: `ordering` and `delivery` must be valued above the first row |

### Security, tenancy, compliance

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `sensitivity` | prd Scale and Context, Risks: what data, whose | public · internal · personal data · regulated (payment, health, finance) | personal+: Secure `QR`s (time to detect, time to revoke), deletion FRs, an `auditability` value above the first row · regulated: a compliance `decision`; `residency` valued |
| `tenancy` | prd Actors, Scope | single tenant · multi-tenant pooled · multi-tenant siloed | pooled: tenant-isolation FRs and a cross-tenant-leak `QS`; per-tenant metrics with bounded cardinality (Operable) · siloed: a provisioning `decision` |
| `trust-boundary` | prd Actors: who can reach it | internal only · authenticated partners · public internet | partners+: authn / authz `decision`; rate-limit FRs · public: a Secure abuse `QS` with detection time |
| `auditability` | prd Scope, Risks; regulation | none · operational log · regulatory audit trail (immutable, retained) | operational+: an audit component `decision`; a Secure completeness `QR` · regulatory: retention and immutability Measures |
| `residency` | prd Scale and Context: where actors and data live | none · one region · per jurisdiction | region+: a deployment-topology `decision` (`topology` valued accordingly) |

### Operations

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `topology` | derived from `availability`, `residency`, `scale` | single node · several instances, one region · multi-region active-passive · multi-region active-active | derived: must not oblige less than those three dimensions imply; `chaos` scenarios at node, zone, or region matching the value |
| `operations-model` | prd Actors (operators), Risks | unattended (nobody on call; must self-heal) · business hours · 24×7 on-call | unattended: Operable `QR`s on self-recovery with `runtime` observe scenarios · 24×7: Operable detection-time `QR`, runbooks in `operation/`, alert routing |
| `deploy-tolerance` | prd User Experience, Risks | maintenance windows acceptable · zero-downtime | zero-downtime: a rolling / blue-green `decision`; an Operable `QS` on error rate during deploy |
| `observability` | derived from `operations-model`, `availability` | logs · metrics and alerts · distributed tracing | metrics+: a telemetry `decision`; an Operable detection-time `QR` · tracing: causation propagation FRs |

### Change and structure

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `maturity` | idea Value, prd Vision: how long this code must live | experiment · growing product · stable core | growing+: Maintainable `fitness` `QR`s on dependency direction and cycles (`CODE_QUALITY.md` gates) · stable: mutation and coverage gates at the `TESTING.md` bar |
| `team-shape` | prd Actors (builders), Constraints | one team · several teams | several: a bounded-context / module-boundary `decision`; a Maintainable `fitness` `QR` on cross-boundary dependencies |
| `integration-surface` | prd Risks and Dependencies, Actors (systems) | none · internal consumers · partner APIs · public API | partner+: a Flexible compatibility `QR` with contract tests; a versioning `decision` |
| `portability` | idea Constraints, prd Risks | one platform · several clouds or on-premises | several: a Flexible `QR` on install time; an abstraction-boundary `decision` |

### Cost and safety

| Dimension | Read from | Values, least obliging first | Drives |
| --- | --- | --- | --- |
| `cost-posture` | prd Risks, Constraints: budget | minimise idle cost · predictable capacity · latency over cost | idle: a hosting `decision` (scale-to-zero); an Efficient cost-per-unit Measure · predictable: a capacity-reservation `decision` |
| `harm` | prd Risks: what a wrong output does to a person | none · financial · physical | financial+: Safe `QR`s with fail-safe FRs; an approval or reconciliation `decision` · physical: hazard analysis in `design/` |

All first values = the smallest legitimate system (`build` scenarios only, one
instance, one team). Still written out, so the doc that changes when a
dimension moves is known.

## Sources

Template synthesis; no single standard. Scale, traffic shape, consistency:
Kleppmann, *Designing Data-Intensive Applications* (2017); `partition-preference`:
Abadi, PACELC (2012). Availability, recovery, operations, observability: Beyer
et al., *Site Reliability Engineering* (2016); AWS Well-Architected (reliability,
cost; SaaS lens for `tenancy`). Latency classes, `dependency-hardness`: SEI
general scenarios (Bass, Clements, Kazman, 2021). `team-shape`: Conway's law via
DDD strategic design; `maturity`: Fowler, sacrificial architecture. Drives cells:
arc42 example requirements and solution approaches (quality.arc42.org). Amend
through a `decision`.

---
id: quality-00001-example-slug
type: quality
status: draft|active|archived
parent: <prd-id | empty when the quality doc is the entry point>
informs: [<spec-id | plan-id>, ...]               # the docs these requirements are input for; a design points here via its own implements
enforced_by: [<test path>, ...]                    # required when any QS is at the build stage; the tests that fail when it is violated
---

# Quality: <the artifact scope these requirements cover>

> One sentence: what is covered, and which Quality Goals of which `prd` this refines.

## 1. Context

- Artifacts: <the services, endpoints, modules, or stores these requirements apply to>
- Quality Goals refined (ranked, from [<prd-id>](../prd/<prd-id>.md)): <goal 1>, <goal 2>, <goal 3>
- Terms from `CONTEXT.md` this doc relies on; measurement terms (percentile, window, rps) are defined in `QUALITY.md`, not here.

## 2. Profile

One value per dimension of `QUALITY.md` Profile, least obliging first in each list; every value
cites where it was read or the `decision` that chose it. The Drives column says what this value
obliges in this doc and downstream: one `;`-separated entry per Drives entry of the value in
`QUALITY_PROFILE.md`, each ending in its state — the `QR` / `QS` / `decision` id that pays it,
`open`, or `waived: <decision id>`. `n/a` only under the no-runtime exception
(`QUALITY_PROFILE.md` Rules), each cell citing the decision id.

| Dimension | Value | Source | Drives here |
| --- | --- | --- | --- |
| `scale` | M | [prd §Scale and Context](../prd/<prd-id>.md): 40 k merchants, 300 k daily payers, peak 500 rps | latency QR with release + runtime QS → quality-00001-QR-1; stateless scale-out and store sizing decision → open |
| `growth` | step | prd §Vision: two marketplace launches in the horizon | load scenario at planned peak × 2 → quality-00001-QS-1.1; Flexible QR on scale-out time → open |
| `horizon` | 12 months | prd §Vision | every Measure Environment names the horizon → quality-00001-QS-1.1 |
| `data-volume` | large | prd §Scale and Context: 10⁸ invoices | Efficient QR on query time at production data → quality-00001-QR-1; release load on a production-sized set → quality-00001-QS-1.1 |
| `retention` | years | prd §Scope: 7-year invoice retention | Efficient storage cost Measure → open; Secure deletion QR → open; Operable archival runbook → open |
| `traffic-shape` | diurnal | prd §Scale and Context | — |
| `read-write` | read-heavy | prd §Functional Requirements: 20 reads per payment | read replica decision → open; staleness bound in a QS → quality-00001-QS-1.2 |
| `access-pattern` | point lookup | prd §Functional Requirements | — |
| `payload` | small records | prd §Functional Requirements | — |
| `skew` | tenant skew | prd §Actors: three merchants are 40 % of volume | per-tenant limit FR → spec-00001-FR-12; QS under skew → open |
| `consistency` | strong per aggregate | prd §Functional Requirements: balance must be exact after payment | version-checked write FRs → spec-00001-FR-4; conflict QS → open |
| `partition-preference` | consistency-first | prd §Risks: a wrong balance costs more than a refused payment | QS Response refuses rather than degrades → quality-00001-QS-2.1 |
| `ordering` | per key | prd: payments on one invoice in order | partition-key decision → open |
| `delivery` | at-least-once, idempotent | prd §Risks: a double charge is the top risk | outbox / inbox decision → open; idempotency FRs → spec-00001-FR-5; QS → quality-00001-QS-2.1 |
| `transaction-span` | one aggregate | prd §Scope | — |
| `availability` | 99.9 % | prd §Risks: an hour down is a day of support tickets | QR with chaos QS → quality-00001-QR-2; runtime SLO → quality-00001-QS-1.2 |
| `recovery` | minutes / an hour | prd §Risks | backup decision → open; restore Measure → quality-00001-QS-2.1 |
| `dependency-hardness` | ledger DB hard; card provider soft | prd §Risks and Dependencies | one chaos QS per hard dependency → quality-00001-QS-2.1; provider fallback FR → spec-00001-FR-8 |
| `blast-radius` | one tenant | prd §Actors | per-tenant bulkhead decision → open |
| `latency-class` | interactive | prd §User Experience: payer waits on the result | QR Measures at p99 → quality-00001-QR-1 |
| `interaction` | request-response | prd §Functional Requirements | — |
| `sensitivity` | regulated (payment) | prd §Scale and Context | Secure QRs → open; compliance decision → open; regulatory `auditability` → the row below |
| `tenancy` | multi-tenant pooled | prd §Actors | tenant-isolation FRs → spec-00001-FR-2; cross-tenant-leak QS → open |
| `trust-boundary` | public internet | prd §Actors | authn decision → open; rate-limit FR → spec-00001-FR-12; abuse QS → open |
| `auditability` | regulatory audit trail | regulation cited in prd §Risks | audit component decision → open |
| `residency` | one region | prd §Scale and Context | — |
| `topology` | several instances, one region | derived from availability / residency / scale | chaos at node level → quality-00001-QS-2.1 |
| `operations-model` | 24×7 on-call | prd §Actors (operators) | Operable detection-time QR → open; runbooks → open |
| `deploy-tolerance` | zero-downtime | prd §User Experience | rolling-deploy decision → open; deploy QS → open |
| `observability` | metrics and alerts | derived from operations-model | telemetry decision → open |
| `maturity` | growing product | prd §Vision | fitness gates → quality-00001-QR-3 |
| `team-shape` | one team | prd §Constraints | — |
| `integration-surface` | partner APIs | prd §Risks and Dependencies: card provider | contract tests → open; versioning decision → open |
| `portability` | one platform | idea §Constraints | — |
| `cost-posture` | predictable capacity | prd §Constraints | Efficient cost Measure → open |
| `harm` | financial | prd §Risks | Safe QR → open; reconciliation decision → open |

## 3. Quality Requirements

- **quality-00001-QR-1** (Efficient) The payment API answers within its latency budget at the planned peak; the degradation past that peak is `spec-00001-FR-7`.
- **quality-00001-QR-2** (Reliable) The payment API stays available when the ledger database fails over.
- **quality-00001-QR-3** (Maintainable) Domain modules compile and unit-test without the web framework.

## 4. Quality Scenarios

- **quality-00001-QS-1.1** (quality-00001-QR-1) [load | release]
  Source: Creators paying invoices through the web app
  Stimulus: 500 requests per second to `POST /payments`, 30 minutes, realistic card mix
  Artifact: payment API, provider stubbed at its contractual latency
  Environment: production-sized data set, normal operation
  Response: every request is answered
  Measure: p99 ≤ 300 ms, p50 ≤ 80 ms, error rate < 0.1 %

- **quality-00001-QS-1.2** (quality-00001-QR-1) [observe | runtime]
  Source: real traffic
  Stimulus: all `POST /payments` requests
  Artifact: payment API in production
  Environment: normal operation, 28-day rolling window
  Response: requests are answered
  Measure: 99.5 % of requests ≤ 300 ms (SLO); error budget policy in [<operation-id>](../operation/<operation-id>.md)

- **quality-00001-QS-2.1** (quality-00001-QR-2) [chaos | release]
  Source: fault injection
  Stimulus: the primary ledger database instance is killed
  Artifact: payment API and its ledger connection pool
  Environment: staging under 100 rps of synthetic traffic
  Response: requests fail over to the replica; in-flight writes are retried or reported, none silently lost
  Measure: error rate returns below 1 % within 30 s; zero lost writes in reconciliation

- **quality-00001-QS-3.1** (quality-00001-QR-3) [fitness | build]
  Source: a developer
  Stimulus: a commit adds a web-framework import to a domain module
  Artifact: `domain/*` modules
  Environment: CI build
  Response: the Architecture gate fails the build
  Measure: zero framework imports in domain modules, every commit

## 5. Verification Plan

| QS | Method | Stage | Runs with | Evidence lands in |
| --- | --- | --- | --- | --- |
| quality-00001-QS-1.1 | load | release | [PERFORMANCE_TESTING.md](../../PERFORMANCE_TESTING.md) | `record`, report artifact linked |
| quality-00001-QS-1.2 | observe | runtime | [<operation-id>](../operation/<operation-id>.md) | `record` (operation doc, alert wired); the window itself on the dashboard |
| quality-00001-QS-2.1 | chaos | release | [RESILIENCE_TESTING.md](../../RESILIENCE_TESTING.md) | `record`, experiment report linked |
| quality-00001-QS-3.1 | fitness | build | `<test path>` — listed in `enforced_by` above | CI |

## 6. Open Questions

Delete this section once every question is closed.

- quality-00001-QR-1 — is 500 rps the planned peak or the current one? Needs the capacity forecast.
- <QR id> — <what is unknown, and what would close it>

## Links

- Consumed by: <spec ids — mirror of `informs`, kept for readers>
- Realised by: <design ids that declare `implements` on scenarios here>
- Operated by: <operation ids that carry the `runtime` scenarios>
- Decisions: <decision ids that trade between these requirements>

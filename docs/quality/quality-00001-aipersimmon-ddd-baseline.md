---
id: quality-00001-aipersimmon-ddd-baseline
type: quality
status: active
informs: [spec-00001-operation-log-component]
enforced_by: [aipersimmon-ddd/aipersimmon-ddd-archunit/src/test/java/com/aipersimmon/ddd/archunit/ModuleNamingChecksTest.java, aipersimmon-ddd/aipersimmon-ddd-operation-log-engine/src/test/java/com/aipersimmon/ddd/operationlog/engine/pipeline/DefaultOperationLogsMetricsTest.java]
---

# Quality: AiPersimmon DDD library baseline

> The measured quality requirements the `aipersimmon-ddd` library holds at build time: framework
> freedom of the contract tier, and bounded metric cardinality in the operation-log engine.

## 1. Context

- Artifacts: the contract modules under `aipersimmon-ddd/` — every module whose artifactId carries
  no technology suffix (`ARCHITECTURE.md` §5.2) — and the operation-log engine's metrics port.
- Quality Goals refined (ranked, `ARCHITECTURE.md` §1; this repo has no `prd`, so this doc is an
  entry point): goal 1, framework freedom → `quality-00001-QR-1`. Goal 2, correctness under
  concurrency and redelivery, is a set of behaviours (one version-checked write, atomic outbox,
  idempotent inbox, replay-safe process manager) — by the `QUALITY.md` FR / QR test they are
  Unwanted system requirements, owned by `ARCHITECTURE.md` §6 until their specs exist. Goal 3,
  piecemeal adoption, has no measure yet and is not refined here.
- Origin of `quality-00001-QR-2`: `spec-00001` §6, moved here when the `quality` namespace was
  introduced.
- Exemptions from `quality-00001-QR-1`, recorded in `ModuleNamingChecks` with reasons: the four
  build-tooling modules (`-bom`, `-quality-config`, `-archunit`, `-test-support`) and the bundle
  `aipersimmon-ddd-starter*`, which is assembly rather than contract.
- Every scenario is `build`-stage: the library ships no deployment of its own, so `release` and
  `runtime` scenarios, and the `chaos` scenarios for the stores and broker a consuming service
  depends on, belong to that service's quality doc (the scaffold under `aipersimmon-ddd-scaffold/`
  is where one would start).

## 2. Quality Requirements

- **quality-00001-QR-1** (Maintainable) A module a domain layer may depend on declares no
  framework dependency: the count of contract modules naming `org.springframework` or
  `com.baomidou` outside test scope is zero.
- **quality-00001-QR-2** (Operable) Operation-log metrics stay low-cardinality: the label set on
  every append counter is exactly `operationCode`, `outcome`, `sinkType` — three labels, none
  per record or per tenant.

## 3. Quality Scenarios

- **quality-00001-QS-1.1** (quality-00001-QR-1) [fitness | build]
  Source: a developer
  Stimulus: a commit adds an `org.springframework` or `com.baomidou` dependency outside test scope to a module whose artifactId has no technology suffix
  Artifact: the declared dependencies in the reactor poms of every contract module
  Environment: `mvn -f aipersimmon-ddd/pom.xml install` on every push to a non-docs path (CI) and on every local build
  Response: `ModuleNamingChecksTest#everyModuleInThisReactorObeysTheNamingRules` fails and names the module
  Measure: zero contract modules declaring a framework dependency, checked once per build

- **quality-00001-QS-2.1** (quality-00001-QR-2) [test | build]
  Source: the operation-log pipeline
  Stimulus: one successful append
  Artifact: `DefaultOperationLogs` and its `OperationLogMetrics` port
  Environment: unit test with a recording metrics stub, every build
  Response: the attempted and succeeded counters are emitted with the append tags
  Measure: the tag value is `AppendTags(operationCode, outcome, sinkType)` — a record of exactly three components, so a fourth label cannot be emitted through this port

## 4. Verification Plan

| QS | Method | Stage | Runs with | Evidence lands in |
| --- | --- | --- | --- | --- |
| quality-00001-QS-1.1 | fitness | build | `ModuleNamingChecksTest#everyModuleInThisReactorObeysTheNamingRules` — the `CODE_QUALITY.md` §2 Architecture gate | CI |
| quality-00001-QS-2.1 | test | build | `DefaultOperationLogsMetricsTest#success_emits_attempted_succeeded_and_both_latencies_with_tags` — an ordinary Surefire test | CI |

Both run under `mvn -f aipersimmon-ddd/pom.xml install`; both paths are in `enforced_by`. The
latency metrics of the same port carry fewer labels (`sinkType` only, or none) and are outside
`quality-00001-QR-2`; whether a Micrometer adapter adds labels of its own is not covered here.

## Links

- Consumed by: spec-00001-operation-log-component §6
- Indexed by: `ARCHITECTURE.md` §1, §10

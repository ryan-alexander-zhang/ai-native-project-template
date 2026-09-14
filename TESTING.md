# Testing

## Purpose

Minimum testing standard for this repo: which level to test at, which tests a
change requires, when work is done.

A test here proves a behaviour under one controlled stimulus — the `test`
method of [QUALITY.md](QUALITY.md). The Performance and Resilience levels run
the `load` and `chaos` methods; what they must show and the evidence they owe
is defined by the quality scenario.

## Test Pattern

- test the behavior you changed
- lowest test level that proves it
- deterministic, easy to read, one behavior per test
- every bug fix adds or updates a regression test

## Test Levels

### Unit

Business logic, validation, mapping, small decision logic. Fast, deterministic,
no real infrastructure.

### Integration

When correctness depends on a real boundary: database, migrations, messaging,
filesystem, framework wiring, external service clients. Test the boundary that
matters; isolate data; independent of execution order.

### API

When the behavior is an HTTP contract or endpoint workflow without full UI
coverage. Verify request and response at the boundary — status, shape, key
side effects; smaller and cheaper than E2E.

### E2E

Only critical user flows, high-risk system flows, smoke checks. Few; full happy
path first; only the most valuable failure paths.

### Performance

Runs a `load` quality scenario: stated volume and concurrency against the
artifact, in the scenario's environment.

- reproduce the scenario's Source, Stimulus, and Environment lines, not a subset
- run long enough for the Measure's window and percentile to be meaningful
- produce a report (percentiles, throughput, error rate, duration, environment) the `record` links
- run at the declared stage — `release` before the plan resolves, `build` only for micro-benchmarks

### Resilience

Runs a `chaos` quality scenario: inject the named fault, observe the response.

- state the hypothesis (the scenario's Response and Measure) before injecting
- one fault at a time, blast radius bounded and written down
- restore the system and prove it, or the experiment is not finished
- produce an experiment report (hypothesis, fault, observed response, blast radius, restore) the `record` links
- run at the declared stage — `release` in a test or staging environment, `runtime` as a game day

## Test Level Guides

Project-specific framework choice per level:

- [UNIT_TESTING.md](UNIT_TESTING.md)
- [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)
- [API_TESTING.md](API_TESTING.md)
- [E2E_TESTING.md](E2E_TESTING.md)
- [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md): load tooling
- [RESILIENCE_TESTING.md](RESILIENCE_TESTING.md): fault-injection tooling

## Testing Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | Manually verify the edited text, links, commands, and examples. |
| Pure logic change | Unit tests. |
| Database or persistence change | Unit and integration tests. Verify migrations if schema changed. |
| API or HTTP contract change | API and/or integration tests. Verify request, response, key side effects. |
| Messaging or async workflow change | Unit and/or integration tests. Verify the contract or workflow behavior. |
| Critical user or system flow change | Relevant tests plus an E2E or smoke check of the changed flow. |
| Change to an artifact a `quality` scenario covers | `build`-stage scenarios stay green (CI, every commit). A `release`-stage scenario is not re-run per change: the delivering `plan` owes it once before `resolved`, and each release that ships the artifact owes it again (`QUALITY.md`, Verification Axis). Note which `release` scenarios the change touches, so the plan's `record` re-runs them. |
| Bug fix | A regression test that would have caught the bug. |
| Refactor with no intended behavior change | Existing tests stay green. Add tests only if coverage is too weak to prove safety. |

## Definition of Done

- requested behavior complete
- matrix tests added or updated, and passing
- no `build`-stage quality scenario fails; `release`-stage scenarios are the plan's Definition of Done, not the change's ([QUALITY.md](QUALITY.md))
- no known regression left behind
- executable code changes: line, branch, and function coverage each ≥ 90%

## Coverage

Line = executable lines run; branch = decision paths run; function = functions
or methods called. Minimum `90%` each for executable code changes. Never mark
work complete below this bar without an explicit exception approved in advance.

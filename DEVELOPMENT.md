# Development

## Purpose

Workflow stages, commands, and Definition of Done for implementation work.
Behavioral principles live in [AGENTS.md](AGENTS.md).

## Development Stages

### Understand

Confirm goal, constraints, affected boundaries. Identify the files and
interfaces that matter; separate facts from assumptions; no silent scope
expansion.

### Plan

- Never build against a `draft` doc; promote it first.
- A feature-sized `spec` (multiple files, real ordering or design choices):
  create or refresh a `docs/plan/` doc, then work it task by task. The plan
  declares `implements: [<the spec>]` and takes the spec's §7 quality
  scenarios into scope; flow ahead of it is `idea -> prd -> quality -> spec`
  ([docs/README.md](docs/README.md)).
- Link the `docs/design/` docs the spec links. Whether a design is needed is
  settled in the spec ([docs/spec/README.md](docs/spec/README.md)); never
  inline design in a `spec` or `plan`.
- Small or localized change: inline reasoning, no plan doc.

### Implement

Unrelated code untouched; existing style and structure matched; only unused
code the change created removed.

### Verify

Run tests per [TESTING.md](TESTING.md) — part of Verify, not a phase after it.
Inspect the diff; confirm the requested behavior is complete.

### Record

When behavior, workflow, or contract changed: update docs; record decisions per
[DOCUMENT.md](DOCUMENT.md).

## Development Guides

- [ARCHITECTURE.md](ARCHITECTURE.md): system structure, boundaries, design constraints.
- [ACCEPTANCE.md](ACCEPTANCE.md): deriving the acceptance set for a spec or rule.
- [QUALITY.md](QUALITY.md): quality requirements — attributes, scenarios, verification methods and stages.
- [TESTING.md](TESTING.md): test levels, required tests, coverage.
- [COMMIT.md](COMMIT.md): commit scope, message rules, hygiene.
- [PR.md](PR.md): PR readiness, review flow, merge rules.
- [SECURITY.md](SECURITY.md): security rules, risk handling.
- [CODE_STYLE.md](CODE_STYLE.md): naming, formatting, consistency.
- [CODE_QUALITY.md](CODE_QUALITY.md): quality gates, thresholds, refactoring order.
- [DOCUMENT.md](DOCUMENT.md): doc taxonomy and management.
- [AUTOPILOT.md](AUTOPILOT.md): the unattended idea-to-PR run.

## Commands

Fill in for the project:

- Setup: `<command>`
- Test: `mvn -f aipersimmon-ddd/pom.xml test`
- Lint: `mvn -f aipersimmon-ddd/pom.xml verify` (runs the Spotless format gate; full quality gate is `verify`) — auto-fix formatting with `mvn -f aipersimmon-ddd/pom.xml spotless:apply`
- Build: `mvn -f aipersimmon-ddd/pom.xml install`
- Run: `<command>`

> Formatting is enforced by Spotless (google-java-format, Google style) at the `verify`
> phase. Before committing Java, run the **full** `mvn spotless:apply` — do **not** use
> `-DspotlessFiles` to scope it: the `verify`-phase check is stricter than the scoped CLI
> check, so a scoped "pass" can still fail the build. See `docs/design/design-00007`.

## Development Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | Document workflow; verify links, examples, location. |
| New feature (spec-sized) | `docs/plan/` first, then task by task, tested per the plan's acceptance path. |
| Small code change | Smallest useful change, smallest relevant checks. |
| Behavior change | Implementation, tests, and affected docs together. |
| Public contract or workflow change | Implementation, tests, and durable documentation together. |
| HTTP API change | Also regenerate `api-tests/openapi.json` ([API_TESTING.md](API_TESTING.md) § OpenAPI). |
| Refactor with no intended behavior change | Behavior unchanged, tests green, diff narrow. |
| Bug fix | For any bug worth tracking, the `docs/issue` doc first (root cause + failing-test reproduction, `docs/issue/README.md`), then the fix with its regression test green. |

## Definition of Done

- requested behavior complete; scope focused
- tracked or non-trivial bug fixes have a `docs/issue` record
- tests pass and meet the [TESTING.md](TESTING.md) DoD
- docs updated per the [DOCUMENT.md](DOCUMENT.md) DoD
- style meets the [CODE_STYLE.md](CODE_STYLE.md) DoD
- quality gates pass per [CODE_QUALITY.md](CODE_QUALITY.md), no raised threshold, no suppressed finding
- security-sensitive changes meet the [SECURITY.md](SECURITY.md) DoD
- no known regression left behind

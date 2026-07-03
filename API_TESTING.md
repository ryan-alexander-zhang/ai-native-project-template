# API Testing

Use this file to record the project-specific API testing choice for this repo.

## Test Framework

Framework: `<fill in for this project>`. Note the short reason it is the chosen default.

This level has no template default. The repo template only pins
[INTEGRATION_TESTING.md](INTEGRATION_TESTING.md) to Testcontainers; pick the
API framework that fits the project's HTTP stack. Prefer a file-based,
git-versioned runner (for example Bruno, Hurl, or a language-native HTTP test
suite) over a shared cloud workspace, so the tests are reviewed and versioned
with the code.

**Precheck**: before running anything, verify the chosen tool is installed
(e.g. `<tool> --version`). If it is missing, stop and ask to install it rather
than falling back to ad-hoc `curl` scripts.

## Command

List the command used to run API tests locally and in CI. It must exit non-zero
when any request, assertion, or test fails — gate CI on that exit code.

## Scope

Define what API tests must cover and what should stay out of API tests.

- **In scope**: the HTTP contract as specced — status codes, the response
  envelope / body shape, idempotency semantics (replay identity, conflict
  codes), auth (401), state-transition conflicts (409), and validation (400)
  vs domain-rule (422) errors. Assert **through the wire only**.
- **Out of scope**: anything owned by lower layers — domain invariants (unit
  tests), persistence and constraints (Testcontainers ITs, see
  [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)), scheduler timing, and async
  consumption. Never reach into the database from an API test.

## Environment

Describe the services, data, and runtime setup required for API tests.

- Document how to bring the stack up and the base URL / auth the suite uses.
- Tests must be **re-runnable against a dirty database**: derive idempotency
  keys per run (never hardcode them) so the suite is repeatable.

## Gate

Define the minimum rule that must pass for API testing at this repo. A new or
changed endpoint should not merge without covering: the happy path, auth
failure, its 4xx error branches, and (for commands) idempotent replay.

## Report

Describe where to check API test results, logs, or CI output. Prefer a runner
that emits both a machine report (JSON/JUnit for CI) and a human-readable
summary (HTML), and publish the CI report as a build artifact.

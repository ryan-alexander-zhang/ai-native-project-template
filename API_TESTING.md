# API Testing

This repo pins **Bruno** as the API testing framework (parallel to
[INTEGRATION_TESTING.md](INTEGRATION_TESTING.md) pinning Testcontainers). Fill in
the project-specific base URL, auth, and command below.

## Test Framework

Framework: **Bruno** — `.bru` collections under `api-tests/`, run with the Bruno
CLI `bru`. Chosen because it gives black-box HTTP tests against the running app,
file-based and git-versioned (no shared cloud workspace), declarative `assert`
blocks plus JS `tests` when logic is needed, and JSON/JUnit/HTML reporters for CI.

**Precheck (run before anything else)**: `bru --version`. If it fails, the Bruno
CLI is not installed — stop and install it: `npm install -g @usebruno/cli`. Do
not fall back to ad-hoc `curl` scripts.

## Command

```bash
# From repo root; the app must be running (see Environment)
bru run --env local -r                    # whole collection, recursively

# Single folder / single request (from api-tests/)
bru run <folder> --env local
bru run <folder>/01-<request>.bru --env local

# Useful flags: --bail (stop on first failure), --tests-only,
# --env-var apiToken=xxx (override one var), --tags smoke
```

Exit code is non-zero when any request, assertion, or test fails — gate CI on it.
Optionally wrap the run in the project task runner (e.g. `make api-test` or an
npm script).

## Scope

- **In scope**: the HTTP contract of each endpoint as specced — status codes,
  the response envelope (e.g. `success` / `data` / `error.code`), idempotency
  semantics (replay identity, fingerprint 409), auth (401), state-transition
  conflicts (409), and validation (400) vs domain-rule (422). Assert **through
  the wire only**.
- **Out of scope**: anything owned by lower layers — domain invariants (unit
  tests), persistence/constraints and outbox (Testcontainers ITs, see
  [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)), scheduler timing, and async
  consumption. Never reach into the database from an API test.

## Environment

- Bring the stack up and start the app first (see [DEVELOPMENT.md](DEVELOPMENT.md)).
  Record the local base URL here: `<http://localhost:PORT>`.
- Environment file: `api-tests/environments/local.bru` holds `{ baseUrl, apiToken }`.
- Collection-level bearer auth lives in `api-tests/collection.bru`; requests opt
  in with `auth: inherit`, negative auth tests use `auth: none`.
- Tests must be **re-runnable against a dirty database**: derive idempotency keys
  per run in a pre-request script (`bru.setVar("extRef", "run-" + Date.now())`),
  never hardcode them.

## Gate

The API test run exits 0 — every assertion and test green. A new or changed
endpoint does not merge without covering: happy path, auth 401, its 4xx error
branches, and (for commands) idempotent replay semantics.

## Report

- Local: the CLI prints per-request pass/fail; write reports to
  `<output-dir>/report.json` (machine) and `report.html` (human) — open the HTML
  for the run summary.
- CI: add `--reporter-junit` and publish it as the test-report artifact.

## Bruno `.bru` Templates (for coding agents)

Layout — one folder per area / bounded context; `seq` controls run order inside a
folder; runtime vars (`bru.setVar` / `bru.getVar`) flow between requests within
one `bru run`:

```
api-tests/
  bruno.json               # collection marker {"version":"1","name":...,"type":"collection"}
  collection.bru           # collection-level auth (bearer {{apiToken}})
  environments/local.bru   # vars { baseUrl: ..., apiToken: ... }
  <area>/01-*.bru …        # requests, ordered by meta.seq
```

### Command request (POST + capture + assert)

```bru
meta {
  name: 01 Submit thing (happy path)
  type: http
  seq: 1
}

post {
  url: {{baseUrl}}/api/v1/things
  body: json
  auth: inherit
}

script:pre-request {
  // unique per run — keeps the suite re-runnable
  bru.setVar("extRef", "run-" + Date.now());
}

body:json {
  {
    "externalRef": "{{extRef}}",
    "amountMinor": 10000
  }
}

assert {
  res.status: eq 200
  res.body.success: eq true
  res.body.data.status: eq OPEN
}

script:post-response {
  if (res.status === 200) {
    bru.setVar("thingId", res.body.data.thingId);
  }
}
```

### Error-branch request (no token / conflict / not-found)

```bru
meta {
  name: 10 Missing bearer token -> 401
  type: http
  seq: 10
}

get {
  url: {{baseUrl}}/api/v1/things?externalRef={{extRef}}
  body: none
  auth: none
}

assert {
  res.status: eq 401
  res.body.success: eq false
  res.body.error.code: eq UNAUTHORIZED
}
```

### JS `tests` block (when assert operators aren't enough)

```bru
tests {
  test("replay returns the same identity", function () {
    expect(res.body.data.thingId).to.equal(bru.getVar("thingId"));
  });
}
```

### Conventions

- Prefer declarative `assert` (operators: `eq`, `neq`, `gt/gte/lt/lte`,
  `contains`, `startsWith`, `endsWith`, `matches`, `length`, `in`, `isNull`,
  `isDefined`, `isTruthy` …); drop to `tests {}` + chai `expect` only for
  cross-request comparisons or computed values.
- Always assert all three of: HTTP status, `res.body.success`, and
  `res.body.error.code` (on failures) — the envelope is the contract. Adapt the
  field names to the project's actual response envelope.
- Non-2xx responses do **not** fail a request by themselves; only assertions /
  tests decide.
- Bruno CLI ≥ v3 runs scripts in safe mode; the patterns above need no
  `--sandbox=developer`.

Reference: Bruno docs — `https://docs.usebruno.com/bru-cli/commandOptions`,
`https://docs.usebruno.com/bru-lang/tag-reference`,
`https://docs.usebruno.com/testing/tests/assertions`.

# API Testing

Pinned framework: **Bruno** (parallel to Testcontainers in
[INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)). Fill in base URL, auth, and
command below.

## Test Framework

**Bruno**: `.bru` collections under `api-tests/`, run with the CLI `bru`.
Black-box HTTP tests against the running app, file-based and git-versioned,
declarative `assert` blocks plus JS `tests`, JSON/JUnit/HTML reporters.

**Precheck**: `bru --version`. On failure install `npm install -g @usebruno/cli`.
Never fall back to ad-hoc `curl` scripts.

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

Non-zero exit on any failed request, assertion, or test; gate CI on it.
Optionally wrap in the task runner (`make api-test`, npm script).

## Scope

- **In scope**: the specced HTTP contract — status codes, response envelope
  (`success` / `data` / `error.code`), idempotency (replay identity, fingerprint
  409), auth (401), state-transition conflicts (409), validation (400) vs
  domain-rule (422). Assert **through the wire only**.
- **Out of scope**: domain invariants (unit), persistence/constraints and outbox
  (Testcontainers ITs, [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md)),
  scheduler timing, async consumption. Never reach into the database.

## Environment

- Stack up and app running first ([DEVELOPMENT.md](DEVELOPMENT.md)). Local
  base URL: `<http://localhost:PORT>`.
- `api-tests/environments/local.bru` holds `{ baseUrl, apiToken }`.
- Collection-level bearer auth in `api-tests/collection.bru`; requests use
  `auth: inherit`, negative auth tests `auth: none`.
- **Re-runnable against a dirty database**: idempotency keys derived per run in
  a pre-request script (`bru.setVar("extRef", "run-" + Date.now())`), never
  hardcoded.

## OpenAPI

Every HTTP API ships a framework-generated OpenAPI 3 spec, never hand-written.
A test fetches it into `api-tests/openapi.json`; the run fails when the
committed file differs, so contract changes show in the PR diff. Exposure:
[SECURITY.md](SECURITY.md) § API Surface Exposure.

## Gate

Run exits 0. A new or changed endpoint covers: happy path, auth 401, its 4xx
branches, and (commands) idempotent replay. `api-tests/openapi.json` current.

## Report

- Local: `<output-dir>/report.json` (machine), `report.html` (human).
- CI: `--reporter-junit`, published as the test-report artifact.

## Bruno `.bru` Templates (for coding agents)

One folder per area / bounded context; `seq` orders requests in a folder;
runtime vars (`bru.setVar` / `bru.getVar`) flow between requests in one run:

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

- Prefer declarative `assert` (`eq`, `neq`, `gt/gte/lt/lte`, `contains`,
  `startsWith`, `endsWith`, `matches`, `length`, `in`, `isNull`, `isDefined`,
  `isTruthy` …); `tests {}` + chai `expect` only for cross-request or computed
  values.
- Always assert HTTP status, `res.body.success`, and `res.body.error.code` (on
  failures); adapt field names to the project's envelope.
- Non-2xx does **not** fail a request; only assertions / tests decide.
- Bruno CLI ≥ v3 runs scripts in safe mode; no `--sandbox=developer` needed.

Reference: Bruno docs — `https://docs.usebruno.com/bru-cli/commandOptions`,
`https://docs.usebruno.com/bru-lang/tag-reference`,
`https://docs.usebruno.com/testing/tests/assertions`.

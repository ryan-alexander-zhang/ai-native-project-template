# Security

## Purpose

Minimum security workflow standard: when a change has security impact, which
risks are reviewed, when a security-sensitive change is done.

## Security Pattern

- auth, access, secrets, and data exposure are explicit concerns
- least privilege that still solves the problem
- no new sensitive data flow without a clear need
- verify security-sensitive behavior directly
- document unresolved risk; never assume safety

## Security Areas

- **Secrets** — never hardcode secrets, tokens, credentials, private keys.
- **Auth and Access** — review authentication, authorization, and permission boundaries before changing them.
- **Data Handling** — review how sensitive data is accepted, stored, logged, returned, deleted.
- **Dependencies and Supply Chain** — review new dependencies, external integrations, generated artifacts before trusting them.
- **API Surface Exposure** — generated API docs (OpenAPI, Swagger UI) and debug endpoints serve only under non-production profiles; production returns 404 by default, proven by a test.
- **Change Review** — escalate unclear security impact.

## Security Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | No secrets, unsafe examples, or misleading security guidance. |
| Auth or permission change | Review access boundaries; verify the intended access rules. |
| Sensitive data flow change | Review collection, storage, logging, output, deletion. |
| Dependency or integration change | Review trust boundaries, configuration, new external risk. |
| Infrastructure or runtime config change | Review exposed services, credentials, default access. |
| Security bug fix | Verify the risk is closed and no equivalent gap remains. |

## Definition of Done

- security impact identified
- no secrets introduced or exposed
- access and data exposure reviewed where relevant
- required checks completed
- unresolved risk documented

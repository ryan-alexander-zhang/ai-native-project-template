# Security

## Purpose

This file defines the minimum security workflow standard for this repo.

Use it to decide:
- when a change has security impact
- what security risks must be reviewed
- when a security-sensitive change is done

## Security Pattern

Keep security simple:
- treat auth, access, secrets, and data exposure as explicit concerns
- prefer the least privilege that still solves the problem
- avoid introducing sensitive data flow without a clear need
- verify security-sensitive behavior directly
- document unresolved security risk instead of assuming safety

## Security Areas

### Secrets

Do not hardcode secrets, tokens, credentials, or private keys.

### Auth and Access

Review authentication, authorization, and permission boundaries before changing them.

### Data Handling

Review how sensitive data is accepted, stored, logged, returned, and deleted.

### Dependencies and Supply Chain

Review new dependencies, external integrations, and generated artifacts before trusting them.

### Change Review

Escalate unclear security impact and review sensitive changes with extra care.

## Security Matrix

| Change type | Minimum requirement |
| --- | --- |
| Docs-only change | Do not add secrets, unsafe examples, or misleading security guidance. |
| Auth or permission change | Review access boundaries and verify the intended access rules. |
| Sensitive data flow change | Review collection, storage, logging, output, and deletion behavior. |
| Dependency or integration change | Review trust boundaries, configuration, and new external risk. |
| Infrastructure or runtime config change | Review exposed services, credentials, and default access behavior. |
| Security bug fix | Verify the original risk is closed and no equivalent gap remains. |

## Definition of Done

A security-sensitive change is done only when all of these are true:

- the relevant security impact was identified
- secrets were not introduced or exposed
- access and data exposure were reviewed where relevant
- the required checks or verification were completed
- unresolved security risk was documented clearly

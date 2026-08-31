---
id: integration-00001-example-slug
type: integration
status: draft|active|archived
informs: [<id>, ...]                          # the docs these notes are input for
---

# Integration: <the external system>

> One sentence: what we use this system for.

## 1. Capabilities

- <what the third party can do that we rely on, with the version or plan it needs>

## 2. Interface Constraints

- <endpoint, quota, rate limit, payload shape, or timeout we must design around>

## 3. Callbacks and Webhooks

- <event, delivery guarantee, retry and ordering behaviour, idempotency key>

## 4. Auth and Permissions

- <credential type, scope, rotation, and where the secret lives>

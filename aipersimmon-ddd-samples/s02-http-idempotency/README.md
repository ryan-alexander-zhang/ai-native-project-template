# S2 — Idempotency, replay protection and rate limiting

The three protections the library offers at the HTTP edge, what each one does **not** cover, and where
a business uniqueness rule takes over from all of them.

Companion document: `docs/analysis/analysis-00017-samples-http-idempotency.md`.

## Run it

```bash
docker compose up -d
mvn -pl s02-http-idempotency -am spring-boot:run    # from aipersimmon-ddd-samples/
```

```bash
# the same key twice: one order, the first answer replayed byte for byte
curl -sS -X POST localhost:18020/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: key-1' -d '{"clientReference":"ref-1","amountCents":1000}'
curl -sS -X POST localhost:18020/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: key-1' -d '{"clientReference":"ref-1","amountCents":1000}'

# a NEW key for the same business order: the unique index answers, 409
curl -isS -X POST localhost:18020/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: key-2' -d '{"clientReference":"ref-1","amountCents":1000}'

# the webhook without a signature: 401
curl -isS -X POST localhost:18020/webhooks/payment \
  -H 'Content-Type: application/json' -d '{"paymentId":"pay-1"}'
```

Signing a webhook by hand is awkward, so `WebhookReplayProtectionTest` contains the client half of the
contract — read it rather than reconstructing it.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| A retry buys once, answer replayed | config only; no application code | `IdempotentWriteTest` |
| Only 4 headers survive a replay | — | `IdempotentWriteTest` |
| 4xx is a decided outcome and replays; 5xx does not | — | `IdempotentWriteTest` |
| The fingerprint does **not** include the body | — | `IdempotentWriteTest.aDifferentBodyOfTheSameShapeIsNOTDetected` |
| Idempotency-Key vs a business unique index | `ClientReference` + `uq_s02_order_client_reference` | `IdempotentWriteTest.adifferentKeyForTheSameBusinessOrderIsTheUniqueIndexsJob` |
| Signature verification (the one bean with no library default) | `HmacRequestSignatureVerifier` | `WebhookReplayProtectionTest` |
| A signature alone does not stop a replay — the nonce does | `replay.nonce.enabled: true` | `WebhookReplayProtectionTest.theSameSignedBytesCannotBeSentTwice` |
| Quota headers, and the fourth request | — | `StricterSettingsTest` |
| Layering rules | — | `ArchitectureTest`, `PackageInfoTest` |

18 tests. `mvn -pl s02-http-idempotency -am verify` runs them; they need Docker (PostgreSQL for the
aggregate, Redis for the edge store) and **skip** rather than fail without it.

## Three things worth knowing before you copy this config

**`replay.enabled=true` does nothing without a `RequestSignatureVerifier` bean.** The filter is
`@ConditionalOnBean` on it, so the application starts, logs nothing, and accepts every unsigned
request. Checked by deleting the `@Component` from `HmacRequestSignatureVerifier`: five assertions in
`WebhookReplayProtectionTest` flip from 401 to **200 OK** — unsigned, tampered, stale and replayed
requests all sail through.

**`require-key` cannot be scoped to a path.** Idempotency has no `url-patterns`, only `methods`, so
turning `require-key` on covers every POST in the application — including the third-party callback,
and no payment provider sends an `Idempotency-Key`. That is why it is off here and demonstrated in a
separate context instead.

**The fingerprint hashes method, path, query, content type and content *length* — not the body.** Two
different requests of the same length collide, and the second is served the first's response. Scope
keys per operation.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| `-web-store-jdbc` | Redis needs no DDL, no Flyway component and no cleanup tuning. The JDBC store is a fine choice for a service that already owns a database — the companion document compares them. |
| Handling the callback properly | Translating it, making its effect idempotent, reconciling when it never arrives: S7. |
| Naturally idempotent commands | Making the *domain* tolerate repetition, rather than the edge: S8. |
| Tenant-scoped keys | All three stores prefix by tenant already; showing it needs a second tenant, which is S13. |

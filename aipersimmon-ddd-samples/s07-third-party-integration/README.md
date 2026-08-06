# S7 — Integrating with a system nobody here controls

A payment gateway: it has its own vocabulary, its own availability, its own idea of how many times to tell
you something, and no interest in our conventions. Two modules, and only one of them is ours.

Companion document: `docs/analysis/analysis-00031-samples-third-party-integration.md`.

## Run it

```bash
# 40 tests, from aipersimmon-ddd-samples/
mvn -pl s07-third-party-integration/payment-service -am verify
```

They need Docker (PostgreSQL) and **skip** rather than fail without it. The provider is not a container: it
is a real HTTP server on a real socket, started inside the test JVM from the `gateway-stub` module.

To drive the whole round trip by hand:

```bash
mvn -pl s07-third-party-integration/gateway-stub -am package
java -jar s07-third-party-integration/gateway-stub/target/s07-gateway-stub-0.1.0-SNAPSHOT.jar \
     18072 s07-gateway-secret http://localhost:18070/gateway-callbacks/charges

mvn -pl s07-third-party-integration/payment-service -am spring-boot:run

curl -sS -X POST localhost:18070/payments -H 'Content-Type: application/json' \
     -d '{"orderRef":"order-1","amountMinor":4500}'
curl -sS localhost:18070/payments/<id>

# make the provider misbehave, then request another payment
curl -sS -X POST localhost:18072/_control -d '{"mode":"SILENT"}'
```

`GatewayMode` is the list of ways it can misbehave, and every one of them is why some piece of the payment
service exists.

## Three channels, and the third is the one people skip

| Channel | Direction | Triggered by | Without it |
| --- | --- | --- | --- |
| The charge request | us → provider | the outbox relay, after the commit | a crash between "the customer clicked pay" and "the provider heard" loses the intent |
| The callback | provider → us | them, best effort | no outcome |
| **Reconciliation** | us → provider | a timer | one lost callback strands that payment forever |

Everything works in testing, where the callback always arrives. The pull channel is what makes the design
survive the week it does not.

## The spine

**Every channel here is at-least-once, so every writer is idempotent. And when there is no answer, the only
correct automatic action is to involve a human.**

## Outbound: no HTTP in the handler

`RequestPaymentHandler` writes the aggregate and one outbox row, in one transaction, and makes no call. The
tempting last line — `gateway.charge(...)` — is wrong three separate ways:

1. **A database connection is held across a network wait.** One slow morning at the provider drains the pool
   and takes down endpoints that have nothing to do with payments.
2. **The call cannot be rolled back.** Anything failing after it reverses the row and not the charge.
3. **The customer waits for the provider** to answer something that is asynchronous anyway.

### The outbox as an outbound-call pipe

`ChargeRequested` is `@Externalized("gateway:charges")` — not a topic, a routing key this application's own
`EventDestinations` produces and its own dispatcher understands. What that buys, for free: a row that commits
with the aggregate, a lease so two instances cannot send it at once, backoff, and a dead letter. None of it
is broker-specific. What it costs:

| | |
| --- | --- |
| **One dispatcher per application** | Every default backs off with `@ConditionalOnMissingBean`, so defining ours also removed the in-process delivery of LOCAL events — silently, since the relay marks a row sent whenever dispatch returns. The library's javadoc says to compose; we compose, and a test proves the leg is really there. |
| **The relay is a courier, not a translator** | The 202's `txn_ref` is logged and dropped. Writing it would put an aggregate transition inside a transport adapter, outside any command. |
| **Therefore the provider's contract had to suit** | Ours answers 202 and nothing of consequence. A provider that returns the *decision* synchronously is a poor fit for this pipe: you would be ignoring an answer you already have. That is a real trade — the alternative is your own task table and a worker that can write both the attempt and the answer. |

### The idempotency key, and the two failures that look alike

`PaymentId` is also the `Idempotency-Key`. It exists before the first attempt and never changes, which is the
whole safety argument. The distinction that matters, and that a negative control forced into the open:

| Failure | Did money move? | With a per-attempt key |
| --- | --- | --- |
| 503 before the charge exists | No | still one charge — **this case cannot prove anything about the key** |
| Charged, then the response was lost | Yes | **two debits** (measured: `chargesCreated` = 2) |

So "we got an error, so nothing happened" is never a safe reading of a remote call — and the stub has a
`LOSE_FIRST_RESPONSE` mode because only the second row is a real test.

## Inbound: three guards, and the one thing they cannot do

The callback endpoint is an unauthenticated POST from the public internet. The library's
`ReplayProtectionFilter` verifies the signature, bounds the timestamp, and rejects a nonce it has seen. The
store behind it is `aipersimmon-ddd-web-store-mybatis-plus` — the library ships two edge stores, this one and
Redis. S2 took Redis; this takes the relational one, so both are covered.

**A signature and a nonce stop the same bytes being sent twice. They do not stop the same outcome being sent
twice.** The provider mints a fresh `event_id`, nonce and signature per delivery, so two "charge approved"
notifications differ in every byte, both are authentic, and the guard is right to accept both. Deduplicating
that is the aggregate's job, and no edge filter can do it.

Two configuration traps, both marked in `application.yaml`:

- `signature-header` and `timestamp-header` live under `replay`; the nonce header lives under `replay.nonce`.
  Rename the first two and forget the third and every authentic callback 401s on the *header* check — a
  failure that looks exactly like an attack and is entirely ours.
- `nonce.enabled=false` was not the "one less layer" switch it reads as. The filter read the nonce header
  only when it was on, so a scheme that signs the nonce — the scheme this library recommends — failed to
  verify **every** authentic callback, and said "Invalid signature" while doing it. `issue-00162`, fixed:
  the header is read unconditionally, dedup still follows the switch. Re-measured with the same control
  below: 7 red before, **exactly 1 after**.

## Out of order, contradictory, unintelligible

`PaymentStatus` is ranked, and the aggregate compares states rather than trusting arrival order or the
sender's clock (which is theirs, and which a redelivery reports as the original send time). A notification
that says less than what we know returns `SUPERSEDED` and changes nothing — except that it still supplies
`gateway_ref` if we lack it, because a late `ACCEPTED` is stale about the status and correct about the
identity.

Three inputs have no correct automatic resolution, and all three are answered **2xx**, keep what we know, and
raise a flag:

| Input | Why no rule works | 
| --- | --- |
| Success then failure for one charge | Nothing chooses correctly between "charged" and "refused": one ships goods for free, the other refuses a paying customer |
| A result code we do not map | "Unknown means failed" is the most expensive line such an integration can contain — the day they add a code for a *successful* charge under a new scheme, every one is recorded as a failure |
| A reference we have no payment for | **404** instead, because it means the callback URL is shared with another environment. No race can cause it: the request leaves only after the row commits |

The first two are 2xx because a provider redelivers until it succeeds, and redelivery will not produce a code
we understand. Note that `PaymentStatus` has no member for "we do not know": that is a state of our
information, modelled as `review_reason`, which is what lets a callback that finally arrives still settle a
payment somebody has been asked to look at.

## Reconciliation, and the bug in the first version of it

Same shape as S11's sweep: an advisory scan, one command per id, a readable report. Two things worth reading:

- **No lease.** S11 argues that work with nothing to version — "call a partner API" — needs a claim first.
  This round does not, because what it does at the provider is a *query*: two instances get the same answer
  and pay for two requests, and the write that follows is version-checked. The moment it re-*sent* a charge
  that would change.
- **One command, two roads.** The callback and the reconciler produce the same `RecordGatewayResult`; only
  `channel()` differs. Two settlement paths would be two implementations of one rule, and the buggy one is
  always the one that runs a hundred times less often.

**What the first version got wrong.** It escalated `NoRecord` (the provider's 404) immediately. Running both
schedules for real caught it: the reconciler's timer beat the relay's by thirteen milliseconds, asked about a
payment that had not been sent yet, got a 404 — and because an escalation is sticky, the scan excluded that
payment forever and it stayed `REQUESTED`. The fix was to the design, not the test: **be patient about
absence.** Both "we do not know" answers wait for `give-up-after`. Which also gives `stale-after` a floor —
it must exceed the time the outbound channel needs to deliver — and that is now written in the yaml.

## Layering: the anticorruption layer is one sealed package

`payments/infrastructure/gateway` holds both directions: the client, the outbound dispatcher, the four wire
records, the code table, the verifier — **and the callback controller**. Every type in it is package-private,
so "which classes know that `51` means declined" is answered by the compiler.

The controller's placement is this sample's one departure from the other samples' layout, argued in its
javadoc: a callback endpoint is not our API, it is the return path of an outbound call. Its URL, auth scheme,
payload and retry behaviour are all the provider's, and changing providers would not modify it — it would
delete it. Keeping it here is what lets both directions share one code table, which is the piece that must not
drift.

## Code tour

| Concern | Where | Verified by |
| --- | --- | --- |
| Intent and outbox row in one transaction | `RequestPaymentHandler` | `OutboundIntentTest` |
| Destination decided at write time | `GatewayDestinations` | `theoutboxRowRemembersWhereItWasGoing` |
| One dispatcher, two legs | `GatewayConfiguration`, `ChargeRequestOutboxDispatcher` | `alocalEventStillReachesItsListener...` |
| At-least-once made safe | the `Idempotency-Key` header | `achargeWhoseResponseWasLostIsRetriedAndTheCustomerIsDebitedOnce` |
| A 400 is hopeless, a 429 is not | `GatewayFailureClassifier` | `arefusedRequestIsDeadLettered...` |
| Signature, window, nonce | `application.yaml` + `GatewayCallbackSignatureVerifier` | `CallbackIngestionTest` (5 refusals) |
| Transport replay vs business duplicate | `Payment.recordGatewayResult` | `aduplicateNotificationWithAFreshSignature...` |
| Out-of-order delivery | `PaymentStatus` rank | two tests, hand-posted and provider-driven |
| Contradiction and unknown codes | `Payment`, `GatewayResultCodes` | 5 tests across all three channels |
| The hanging state and the pull channel | `PaymentReconciliation`, `HttpGatewayCharges` | `ReconciliationTest` (9) |
| Both timers, nobody driving | `ReconciliationScheduler` + the relay | `UnattendedFlowTest` |
| The sealed anticorruption layer | package-private everything | `ArchitectureTest` (3 rules) |

40 tests.

## Eight negative controls, each run on its own

| Change | Red | 
| --- | --- |
| A fresh key per attempt | 3 — including two debits for one payment |
| No rank comparison | exactly 2, the out-of-order pair |
| `nonce.enabled=false` | 7 at the time — only one about replay; the other six found `issue-00162`. **Re-run after the fix: exactly 1**, the replay one |
| The in-process leg emptied | exactly 1, and the row was still marked sent |
| `GatewayFailureClassifier` unregistered | exactly 1 (no dead letter after one poll) |
| Scan without `review_reason IS NULL` | exactly 1 |
| Unknown code mapped to `FAILED` | exactly 3 — one per inbound road |
| `NoRecord` escalated eagerly | 2, including the unattended end-to-end |

Not repeated here: deleting the `RequestSignatureVerifier` bean, which makes the filter back off and accept
every unsigned request. S2 measured that one.

## Not demonstrated here

| | |
| --- | --- |
| Replaying a dead letter as a fresh attempt | The library has `DeadLetters`; replaying is an operator action. What this sample guarantees is that the reconciler notices. |
| Refunds and reversals | A different business process, not a sequel to this one. |
| Multiple currencies | Configured, not modelled. A multi-currency service puts it on the aggregate. |
| Circuit breakers, bulkheads, retry budgets | Same conclusion as S6: a resilience library or the mesh. |
| Both modules booted against each other | The stub runs in the test JVM, or by hand with `java -jar`. The same gap S4, S21 and S6 have. |

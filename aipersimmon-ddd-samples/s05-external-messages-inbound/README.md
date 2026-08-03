# S5 — Messages from a system that never heard of this library

An ERP owns product master data and pushes changes to Kafka in its own JSON. This service mirrors them.
Nothing about the format, the semantics or the delivery guarantees is ours to decide.

Companion document: `docs/analysis/analysis-00029-samples-external-messages-inbound.md`.

## Run it

```bash
mvn -pl s05-external-messages-inbound -am verify   # 15 tests
```

Real PostgreSQL and real Kafka via Testcontainers; they **skip** rather than fail without Docker.

## What is deliberately absent

`aipersimmon-ddd-starter-messaging-kafka`. The framework's consumer bridge subscribes to the topics its
own `@Externalized` events name, and it requires every record to carry well-formed CloudEvents
attributes — `ce_id`, `ce_source`, `ce_type`, `ce_dataschemaversion` — because those are what the inbox
key and the `(type, version)` catalog lookup are made of. An ERP has none of them and will not grow them
for us.

So this service owns a plain `@KafkaListener`, and with it the three jobs the bridge would have done:

| | who does it in S4 | who does it here |
| --- | --- | --- |
| deserialization | the bridge, from `ce_type` + the catalog | the ACL, by hand, refusing what it cannot map |
| deduplication | the bridge, keyed `(ce_source, ce_id)` | **the handler**, inside the command's transaction — and only where it is needed |
| failure classification | the bridge's three tiers | this sample's own policy, in one place |

## Duplicates are the easy half; ordering is the other one

Two message kinds, chosen to contrast — because which mechanism a message needs is a property of the
message, not a house style.

**`PRODUCT_CHANGED` carries absolute state plus a per-product revision.** The aggregate applies it only
if the revision is newer, so a late message and a redelivery are the same thing from here: news the
product already has. That single comparison covers both hazards, which is why this path uses **no dedup
key at all** — a test asserts the inbox stays empty.

**`PRICE_REDUCED` carries a delta.** No content comparison can tell a redelivery from a second genuine
adjustment, and no ordering token helps, because both deliveries carry the same news. Here the handler
calls the `Inbox` itself, in the command's transaction, so the dedup record and the price change commit
or roll back together — the other branch of the rule S4 established the hard way.

The dedup key comes from the producer (`msg_id`) because nowhere else can it come from. A message with a
delta and no id is **dead-lettered rather than guessed at**, and the reasoning is worth keeping:

| tempting substitute | why it fails |
| --- | --- |
| a hash of the payload | collapses two legitimate identical adjustments into one, and breaks on a re-serialization |
| `(topic, partition, offset)` | not stable across a producer retry, a topic migration or a replay — the exact events dedup exists for |
| an id minted on arrival | a new one per delivery, so it suppresses nothing |

Making the message absolute is a contract change worth asking upstream for. Inventing identity is not an
engineering decision, it is a silent one.

## Why the revision and not the timestamp

`changed_at` is on every message and the sample ignores it. A timestamp from another system is a weak
ordering key: clocks skew between the ERP's own nodes, two changes can share a millisecond, and the
format admits offsets that make comparison a parsing question. `sharingATimestampDoesNotMakeTwoChangesUnorderable`
sends two changes stamped with the same instant and asserts the newer revision still wins — a timestamp
comparison would have had to pick one arbitrarily, and "arbitrarily" means "by arrival", which is the
thing being defended against.

Where an upstream offers only a timestamp, the honest options are to use it and accept that same-instant
changes are unordered, or to ask for a counter. Not to pretend the timestamp is one.

## The failure policy, and that it is ours

| what arrives | what happens |
| --- | --- |
| `DataAccessException` while handling | rethrown, so the container retries — the database being down is not the message's fault |
| unparseable JSON | dead letter, at once |
| unknown `event_kind` | dead letter — **not skipped**: an unknown kind may be traffic this consumer was meant to grow into, and a dropped message leaves nothing to find it by |
| `PRODUCT_CHANGED` with no `rev` | dead letter — without an ordering token a late message will eventually overwrite the truth |
| a price in another currency, or sub-cent | dead letter — a mirror that rounds somebody else's money silently has stopped being one |
| a delta with no `msg_id` | dead letter — see above |
| a defect in this code | dead letter, deliberately: an inspectable record beats a poison message retried forever behind every other product's updates |

The framework's bridge has three tiers and different ones (poison → immediate DLT; `DataAccessException`
→ retried forever, never dead-lettered; everything else → bounded backoff then DLT). A deployment that
would rather stall than dead-letter can invert this sample's policy; what it must not do is leave the
choice implicit, because the unchosen default is "retry ten times with no backoff, then log and move on"
— silent loss for master data.

## Three things this cost, all of them recorded in the code

1. **The dead-letter topic's name was a default, and the default was not `.DLT`.** Six tests failed
   looking for records that had been published to `<topic>-dlt`. The recoverer now names the destination
   explicitly, matching the framework's convention, because a DLT whose name is inherited is a DLT
   somebody will fail to find.
2. **An await that throws is not an await.** `untilAsserted` retries an `AssertionError` and lets
   anything else through, so a `queryForObject` helper failed the whole wait on its first poll with
   `EmptyResultDataAccessException` while the code under test was correct. A wait that cannot express
   "not yet" is not a wait.
3. **Both mechanisms were verified by removing them.** Disabling the revision comparison turns
   `alateChangeDoesNotOverwriteANewerOne` and `sharingATimestampDoesNotMakeTwoChangesUnorderable` red;
   disabling the inbox check turns `aredeliveredRelativeChangeIsAppliedOnce` red. Exactly those three,
   which is what makes them tests of those mechanisms rather than of the happy path.

## Not demonstrated here

| | |
| --- | --- |
| A non-Kafka transport (SFTP drop, HTTP webhook, JMS) | The shape is the same — translate at the edge, classify there, dedup only where the effect needs it. The callback-shaped inbound edge is S7. |
| Publishing our own events about what we mirrored | Nothing here is externalized; that is S4. |
| Replaying the dead-letter topic | S22. |
| Schema evolution of the ERP's format | The upstream owns it and will not version it for us; the additive case is covered (`anExtraFieldTheUpstreamAddedIsIgnored`), the rest is S21's machinery applied to a format that has no version field. |

# S21 — One contract, three revisions, two deployables at different versions

A published event is a public contract. Once it has been sent, it cannot be un-sent, and the records
already on the wire outlive the code that produced them. This sample is about the period — hours during
a rolling deploy, months in practice — when the publisher and its consumers are at different revisions
of the same contract, and about which changes make that period survivable.

Companion document: `docs/analysis/analysis-00026-samples-event-contract-evolution.md`.

## Run it

```bash
mvn -pl s21-event-contract-evolution/ordering-service   -am verify   # 6 tests
mvn -pl s21-event-contract-evolution/inventory-service  -am verify   # 12 tests
```

Real PostgreSQL and real Kafka via Testcontainers; they **skip** rather than fail without Docker.

## The contract's history

| Revision | Shape | What kind of change it was |
| --- | --- | --- |
| v1 | `orderId, customerId, sku, quantity` | — |
| v2 | `orderId, customerId, lines[]` | A **restructuring**. Not expressible as an optional field, so the version had to move; losslessly derivable from v1, so consumers can be carried forward mechanically. |
| v3 | `…, warehouseCode` | **New information**. Derivable from nothing — so the field has to be one whose *absence* means something. |

Those two are deliberately different cases: "needs a version bump" and "can be upcast" are separate
questions, and v2 answers yes to both while v3 answers yes to the first only.

The third kind of change is the cheap one and the one to reach for first: a **purely additive optional
field, with no version bump at all**. An older consumer ignores an unknown JSON property and keeps
working. A test pins it.

## Who holds which revisions

| | tree holds | why |
| --- | --- | --- |
| `ordering-service` (publisher) | **v3 only** | It publishes one revision. Keeping the retired classes would keep the ability to emit them, which is the one thing a contract owner must not have mid-migration. |
| `inventory-service` (consumer) | **v1, v2, v3** + two upcasters | Every revision that can still *arrive*. |

That asymmetry is why there is no shared contract jar in this sample, and the reason is sharper than in
S4: a jar has one version at a time, so it cannot express "the two sides are at different revisions",
which is the normal state of affairs during a deploy.

## What each side's tests pin down

**Publishing (`PublishedRevisionTest`)**

| Claim | Test |
| --- | --- |
| The wire carries the revision this deploy is at, in its own attribute — not in the type name, not in the topic | `theWireCarriesTheRevisionThisDeployIsAt` |
| **A backlog row ships at the revision it was written at** | `abacklogRowShipsAtTheRevisionItWasWrittenAt` |
| The publisher's own census holds one revision; the consumer's holds three | `theRetiredRevisionsAreNotInThisServicesTreeAtAll` |

The middle one is the fact people are surprised by. The outbox row carries the payload, the version and
the destination, all decided in the publishing transaction, so the relay is a courier and not a
translator. **Deleting a revision from the publisher does not stop it being published; draining does.**
A consumer's "how long must I keep reading v1" is therefore
`max(topic retention, publisher backlog drain, dead-letter replay window)` — and the middle term is
invisible from the consumer's side, which is why it has to be told.

**Consuming (`ContractEvolutionTest`, `SilentSkipWhenTheUpcasterIsGoneTest`)**

| Claim | Test |
| --- | --- |
| A v1 record rides both hops to the one v3-typed listener | `av1RecordRidesTheWholeChainToTheOneListener` |
| A v2 record enters the chain where its revision sits | `av2RecordRidesOneHopAndLandsInTheSamePlace` |
| A v3 record passes through and names its own warehouse | `av3RecordArrivesUnchangedAndNamesItsOwnWarehouse` |
| **The upcast invents nothing**, and the envelope describes the payload delivered rather than the wire | `theUpcastDoesNotInventWhatTheRetiredRevisionNeverCarried` |
| A revision this consumer has not adopted is dead-lettered, never guessed at | `arevisionThisConsumerHasNotAdoptedIsDeadLetteredRatherThanGuessedAt` |
| **Dual publishing one fact applies it twice** | `dualPublishingOneFactAppliesItTwice` |
| An optional addition inside one revision needs no bump | `anOptionalAdditionInsideOneRevisionNeedsNoBump` |
| The subscription set is the union of the topics the declared revisions name | `theSubscriptionIsTheUnionOfTheTopicsTheDeclaredRevisionsName` |
| **Deleting only the upcaster makes retired records vanish in silence** | `SilentSkipWhenTheUpcasterIsGoneTest` |

## Deploy order: consumers first

The dead-letter test *is* the publisher-first deploy, seen from the consuming side: a revision the
consumer has no class for, rejected on arrival. Resolution is the exact `(name, version)` pair with no
fallback, which is the right default — a payload read at the wrong revision is a silent
misinterpretation, and that is worse than a loud rejection.

So: **teach every consumer the new revision, let that reach production, and only then let the publisher
emit it.** A consumer that already knows v4 costs nothing while v3 is still being sent. A publisher that
ships v4 first costs one dead letter per order until the consumers catch up.

## The two ways to retire a revision, one loud and one silent

This is the most dangerous thing in the sample, and the `upcasters-removed` profile exists to prove it:

| Cleanup | What happens to a v1 record |
| --- | --- |
| Delete the retired **class** | Unresolvable `(name, version)` → **dead letter**. Loud, countable, replayable. |
| Delete only the **upcaster**, keep the class | Resolves to a class no handler is typed for → **skipped before the inbox**. No effect, no inbox row, no exception, no dead letter, no lag. |

Removing the upcaster feels like the safer half of a cleanup. It is the half that loses orders. The
skip itself is worth having — a service on a busy topic should not write an inbox row for every record
it has no handler for — so `skip-locally-unhandled` is left at its default here rather than turned off
to make the hazard go away.

## The iron law, and why the test reads the payload

What the old revision never carried, the upcast must not invent. The v2 → v3 upcaster leaves
`warehouseCode` null; the *listener* decides that absence means `MAIN`, in one named constant, in the
open.

The negative control is the interesting part: making the upcaster fabricate `"MAIN"` turns exactly one
assertion red and leaves every other test green — because the fabricated value happens to match the rule
the adapter would have applied anyway, so the **effect is identical**. A violation whose effect is
currently indistinguishable is still a violation; it surfaces the day the rule changes, in data written
years before. Only asserting on the payload catches it, which is what the recording listener is for.

## Not demonstrated here

| | |
| --- | --- |
| A mis-declared upcaster failing startup by name | Verified by the library's own `EventUpcasterChainTest` (same logical name, strictly increasing version, no duplicate source, no erased type parameter). Duplicating it here would test the library, not the flow. |
| Both services booted against one broker | As in S4, each side is tested against the wire contract independently. |
| Deleting v1 after the window passes | The decision procedure is in the companion; there is nothing to run. |
| Semantic change under an unchanged shape | Named in the companion (§4) as the case no mechanism catches. |
| Schema migration ordering | S23. This sample only states which of the two orders is safe. |

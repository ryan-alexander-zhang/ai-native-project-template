# S16 — Tactical modelling

What the library's building blocks are for, and which one to reach for: entity vs value object vs
aggregate root, `Invariant` vs `Specification`, `Transitions`, domain services and factories.

Companion document: `docs/analysis/analysis-00016-samples-tactical-modelling.md`.

## Run it

```bash
mvn -pl s16-tactical-modelling -am verify     # from aipersimmon-ddd-samples/
```

That is all there is to run. **This sample has no application**: no HTTP, no database, no Spring, no
`main` method. Its one compile dependency is `aipersimmon-ddd-core`, which names no framework — the
module exists partly to prove that a domain model can be written against this library without
inheriting one. Add `spring-boot-starter-web` here and `domainShouldBeFrameworkFree` turns red.

## Code tour

| Concept | Where | Verified by |
| --- | --- | --- |
| Value object — equality by attributes | `Money`, `Sku`, the three id types | `MoneyTest` |
| Entity — equality by identity, attributes move | `OrderLine` (`@Identity` on `id()`) | `IdentitySemanticsTest` |
| Aggregate root — the only way in | `Order` | all of them |
| Aggregates reference each other by id | `Order` holds `CustomerId` | — |
| `Invariant` refuses (and carries a code) | `OrderHasAtLeastOneLine`, `OrderLinesHaveDistinctSkus`, `OrderTotalWithinCeiling`, `OrderLinesAreStillOpen` | `OrderInvariantTest` |
| `Specification` answers, and composes | `EligibleForFreeShipping`, `NotCancelled` | `SpecificationAndServiceTest` |
| `Transitions` — refusal code belongs to the destination | `Order.TRANSITIONS` | `OrderLifecycleTest` |
| Domain service — belongs to neither object | `LoyaltyDiscount` (`@Service`, not Spring's) | `SpecificationAndServiceTest` |
| Repository port declared by the domain | `Orders` | `ArchitectureTest` |
| Building-block and layering rules | — | `ArchitectureTest`, `PackageInfoTest` |

24 tests, all of them plain JUnit.

Three details worth reading the tests for, because they are easy to get wrong and cheap to get right:

- **`Money` normalises its scale.** Without it, `10` and `10.00` are different money — a record's
  equality delegates to `BigDecimal.equals`, which compares scale.
- **`addLine` checks the invariant against a prospective list**, before the mutation lands. Mutating
  first and checking after leaves a rejected line on the aggregate whenever the caller catches.
- **Declaring two refusal codes for one destination throws while the table is being built**, not on
  some later request. `OrderLifecycleTest` pins that down.

The version-witness rule was checked the only way that means anything: adding `versionAdvanced()` to
`Order.pay()` makes `ArchitectureTest` fail, naming `Order.pay()`. Without that rule running, model
code can disarm the optimistic lock silently.

## Not demonstrated here, on purpose

| | Why |
| --- | --- |
| Persistence | How this model becomes rows — and how `restoreVersion` gets forgotten — is S17. |
| A request driving it | S1 already shows controller → command → handler → aggregate. |
| Cross-aggregate rules | The customer's credit limit is deliberately *not* an invariant of `Order`: it spans two aggregates, which is S8. |
| Publishing the events | `Order` registers them; a repository drains and publishes them. Semantics are S3. |
| Bean Validation | Nothing here validates edge input; the three-layer split is S19. |

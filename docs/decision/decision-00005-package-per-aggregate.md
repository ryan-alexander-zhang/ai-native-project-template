---
id: decision-00005-package-per-aggregate
type: decision
role: main
status: active
parent:
---

# Package per aggregate, with aggregate internals package-private

Refines the domain-layer packaging left open by
`decision-00004-bounded-context-module-structure` (which fixed the BC/layer→module
mapping but not how a bounded context's *domain* is organized internally). Draft
for review. Every claim is backed by a `docs/reference/` note or an external
source under **Sources**.

## Context

A bounded context contains **multiple aggregates** (e.g. the Ordering context has
`Order` and `Customer`, distinct consistency boundaries; `Order` references
`Customer` by id). The question: how is the domain layer packaged?

The samples in `bc-and-layer-samples` currently use a **flat** domain package —
`…ordering.domain` holding `Order`, `OrderLine`, `Customer`, `OrderStatus`,
`Orders`, `Customers` together, distinguished only by class name. This works for
two tiny aggregates but has two problems that grow with the model:

1. The aggregate boundary is invisible in the structure — you cannot tell which
   entity/VO belongs to which aggregate without reading each class.
2. Nothing stops code from constructing or referencing an aggregate's **internal**
   entity directly (e.g. `new OrderLine(...)` or holding an `OrderLine` reference
   from outside), violating the DDD rule that an aggregate is reached **only
   through its root**. It relies on discipline / review, not the compiler.

## Decision

**Organize the domain by aggregate: one package per aggregate, named after the
aggregate root.** Each aggregate package contains its root, its internal entities,
its value objects, its domain events, and its repository port.

```
…ordering.domain
├── order/          Order (root, public)      OrderLine (entity, package-private)
│                   OrderStatus (VO)           Orders (repository port, public)
├── customer/       Customer (root, public)    Customers (repository port, public)
└── shared/         Money and other VOs shared across this BC's aggregates
```

**Make aggregate internals `package-private`; keep only the aggregate's public
surface public.**

- **Public**: the aggregate **root**, its **repository port**, and any VO used in
  the aggregate's public method signatures / published events.
- **Package-private**: internal entities and internal VOs (e.g. `OrderLine`) that
  callers must not touch directly.

Because Java package-private members are visible **only within the same package**,
this makes the DDD rule **compiler-enforced**: outside the `order` package you
cannot `new OrderLine(...)`, cannot declare an `OrderLine` field, cannot return
one — you can only go through `Order`. Encapsulation stops being a convention and
becomes a compile error.

**Reference other aggregates by identity, not by object reference** (`Order` holds
a `customerId`, never a `Customer` instance) — so aggregate packages don't need to
expose their internals to each other.

**Construct internals only via the root.** This is the mechanic that makes
package-private internals work when the repository lives in a *different* package
or module (as in structure-2, where `*-infrastructure` is a separate Maven
module): the repository must not build `OrderLine` itself. Instead the root
exposes a factory / rehydration method that accepts **raw data** and builds its
own internal entities:

```java
// in the order package — infrastructure passes data, never OrderLine instances
Order.rehydrate(id, customerId, List<LineData> lines, status);
```

So the domain module's *public API* is exactly {aggregate roots + repository ports
+ shared public VOs}; everything else stays package-private.

## Scope across layers

The **compiler-enforced encapsulation** above is **domain-only**: only the domain
holds an aggregate's internal entities/VOs, so package-private "reach the aggregate
only through its root" applies there and nowhere else. The other layers have no
such internals to hide.

The grouping is for **cohesion** (Common Closure Principle), not invariant
enforcement, and the **grouping axis follows the layer's nature**:

- **`domain` and `application` → by aggregate / use case.** These are domain
  layers; the aggregate (and the use cases acting on it) is the unit of cohesion.
  Group them `…/order`, `…/customer`. (Domain additionally gets the package-private
  compiler enforcement above; application does not.)
- **`infrastructure` and `adapter` → by technical concern / delivery mechanism.**
  These are *technical* layers, so their natural axis is technology, not domain —
  this is what Alibaba COLA does (`infrastructure` split into persistence /
  gateway-impl / messaging / config; `adapter` by protocol). So
  `infrastructure/persistence`, `infrastructure/messaging`,
  `infrastructure/external`; `adapter/web`, `adapter/messaging`, `adapter/rpc`.
- **Within a concern that is itself aggregate-specific → sub-group by aggregate.**
  Persistence is per-aggregate, so `infrastructure/persistence/order`,
  `infrastructure/persistence/customer`. Cross-cutting concerns (outbox/inbox
  messaging, external gateways) are not aggregate-specific and stay flat under
  their concern.
- A layer that holds only **one** aggregate's / concern's code needn't add a
  sub-package; introduce it when the second arrives.

So aggregate cohesion lives where it matters most (domain + application); the
technical layers are organized technically, with per-aggregate sub-grouping only
*inside* aggregate-specific concerns such as persistence. Do **not** mix axes at
one level (e.g. `infrastructure/{order, customer, outbox, external}` puts two
aggregate names beside two concern names — pick the concern axis for a technical
layer).

"By layer" and "by aggregate" are orthogonal dimensions; which one is the *outer*
dimension is a separate choice compared in
`analysis-00003-aggregate-first-vs-layer-first`. In structure-2 the layer is the
outer dimension (one Maven module per layer) and the aggregate is a sub-package
inside each layer module. Note Java package-private is judged per *leaf* package
(there is no sub-package inheritance of access), so the domain encapsulation
benefit is unaffected by that ordering.

## Rationale & sources

Reference implementations (`docs/reference/`):

- **ddd-by-examples/library** — the `lending` context is split into a package per
  aggregate (`lending/patron`, `lending/book`, `lending/dailysheet`,
  `lending/patronprofile`), and the aggregate roots are **package-private**
  (`Patron` has a package-private constructor). This is package-per-aggregate +
  package-private protection, exactly. (`docs/reference/ddd-by-examples-library/`)
- **ddd-by-examples/factory** — sub-domains as sub-packages; aggregate roots,
  entities, and repository ports are all **package-private**, boundaries enforced
  by "package-private visibility plus module boundaries, not just annotations".
  (`docs/reference/ddd-by-examples-factory/`)

External / big-tech:

- **Alibaba Cloud (COLA authors), "An In-Depth Understanding of Aggregation in
  DDD"** — states directly: put all objects of an aggregate (root, value objects,
  repository, factory) **in the same package, named after the aggregate root**.
  Alibaba COLA's domain layer follows this per-aggregate organization.
- **Eric Evans, _Domain-Driven Design_ — "Modules" pattern**: modules (packages)
  are part of the model; group by conceptual cohesion (the aggregate), not by
  technical type.
- **Vaughn Vernon, "Effective Aggregate Design"**: an aggregate is a transactional
  consistency boundary reached only through its root, and aggregates reference one
  another **by identity** — the two rules that package-per-aggregate + package-private
  internals encode structurally. (Also listed in
  `docs/reference/domain-driven-hexagon/`.)

## Consequences

- Domain layer is reorganized aggregate-first; the aggregate boundary is legible
  from the package tree.
- The "only-through-the-root" rule is enforced at compile time via package-private
  internals — no ArchUnit rule needed for it (though ArchUnit still guards
  layer/BC boundaries).
- **Repositories rehydrate via a root factory that takes raw data**, so internal
  entities can stay package-private even when the repository is in another module
  (structure-2/3). This is a required change from the current samples, where
  `Order.rehydrate` takes `List<OrderLine>` and therefore forces `OrderLine` to be
  public.
- Applies to all three structures: structure-1 (packages in one module),
  structure-2 (packages inside each `*-domain` module — package-private works
  within the module), structure-3 (same, inside each service).
- The `bc-and-layer-samples` currently use flat domain packages; a follow-up
  refactors `ordering-domain` / `inventory-domain` to package-per-aggregate with
  package-private internals to demonstrate this decision.
- Trade-off: more packages, and a small amount of ceremony in the root's
  rehydration API. Accepted — it buys compiler-enforced aggregate encapsulation.

## Sources

- `docs/reference/ddd-by-examples-library/`, `docs/reference/ddd-by-examples-factory/`,
  `docs/reference/domain-driven-hexagon/`
- Alibaba Cloud — An In-Depth Understanding of Aggregation in DDD —
  https://www.alibabacloud.com/blog/an-in-depth-understanding-of-aggregation-in-domain-driven-design_598034
- Alibaba COLA — https://github.com/alibaba/COLA
- Eric Evans, _Domain-Driven Design_ (2003), "Modules"
- Vaughn Vernon, "Effective Aggregate Design" (Parts I–III)

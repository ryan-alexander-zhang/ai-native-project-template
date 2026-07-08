---
id: decision-00004-bounded-context-boundary-realization
type: decision
role: main
status: draft
parent:
---

# Bounded contexts are logical by default; a physical (Maven-module) split is deferred until the boundary is stable

Refines the module layout in `design-00001-java-ddd-template-architecture`. Draft
for review.

## Context

The template decomposes on two orthogonal axes — **by bounded context** (BC,
vertical) and **by layer** (domain/application/infrastructure/adapter/api,
horizontal). `design-00001` (Q8 = B2) makes each **layer** a Maven module for
compile-time isolation. The open question this decision settles: **should each
bounded context also be a Maven module from day one?**

The two ways to realize a boundary differ sharply in cost:

- **Physical boundary — Maven module.** Enforced at **compile time** (a
  disallowed type is off the classpath). Strong, but **expensive to move**: change
  POMs, relocate directories across modules, rewire dependencies.
- **Logical boundary — package + Spring Modulith `verify()`.** Enforced at **test
  time** (CI fails on illegal cross-context access). **Cheap to move**: renaming or
  moving a package is an ordinary refactor.

These axes are not symmetric. **Layer** boundaries are architectural constants —
known on day one, effectively never move. **Bounded-context** boundaries are
domain-driven, **discovered iteratively**, and *will* be re-cut as understanding
deepens. Fixing the most volatile boundary with the most rigid mechanism is the
mistake this decision avoids.

## Decision

- **Layer axis — physical from day one.** Each layer is a Maven module (per
  `design-00001` / B2). Layers do not move, so the strongest enforcement is free
  of churn cost.
- **Bounded-context axis — logical by default.** A BC is realized as a package
  (or package group) and enforced by Spring Modulith
  `ApplicationModules.verify()`. It is **promoted** to its own Maven module — and
  later, if warranted, its own deployable service — **only once its boundary is
  stable and it needs independent build, deploy, or scaling.**
- **The template ships exactly one fully worked bounded context** (as the five
  layer Maven modules of `design-00001`). A not-yet-promoted context is added as a
  Spring Modulith package group (layers as sub-packages, ArchUnit-enforced) and
  promoted to the five-module structure when justified.
- **Cross-context communication is always via published language** (integration
  events / the `*-api` contract), never a direct internal reference — *regardless*
  of whether the context is currently a package or a module. This makes promotion
  a packaging/transport change, not a rewrite.
- **Spring Modulith's role is therefore scoped to:** (a) enforcing logical BC
  boundaries while they are packages; (b) its event publication registry (the
  transactional outbox) for inter-context events; (c) easing extraction via
  `@Externalized`. It is **not** the layer-boundary mechanism — Maven modules are.

Promotion path (contract stable throughout): **package BC** → **Maven-module BC**
→ **separate service** (in-process events → broker; `*-api` facade call → HTTP/RPC).

## Rationale & sources

Distilled reference notes in `docs/reference/`:

- **kgrzybek/modular-monolith-with-ddd** — the modular monolith as a "stepping
  stone" to microservices; boundaries are refactored far more cheaply inside one
  process; schema-per-module so a context can be extracted with no app change.
  (`docs/reference/modular-monolith-with-ddd/`)
- **xsreality/spring-modulith-with-ddd** — bounded contexts as *packages* in one
  Maven module, boundaries verified by `ApplicationModules.verify()`, the JPA
  event publication registry as a transactional outbox, `@Externalized` for later
  broker delivery. (`docs/reference/spring-modulith-with-ddd/`)
- **ddd-by-examples/factory** — build-module split reserved for the pieces that
  need it (`*-model` / `*-adapters`), not applied uniformly.
  (`docs/reference/ddd-by-examples-factory/`)
- **Sairyss/domain-driven-hexagon** — bounded context as a *language/model*
  boundary, distinct from any deployment unit. (`docs/reference/domain-driven-hexagon/`)

External / industry sources:

- **Eric Evans, _Domain-Driven Design_ (2003)** — bounded contexts are discovered
  and refined iteratively ("refactoring toward deeper insight"); boundaries are
  expected to shift.
- **Vaughn Vernon, _Implementing Domain-Driven Design_** — a microservice maps to
  (at most) a bounded context; model boundary and deployment unit are distinct
  axes.
- **Martin Fowler, "MonolithFirst" (2015)** — start with a monolith, find the
  boundaries, extract later; up-front microservice decomposition usually mis-cuts
  boundaries.
- **Sam Newman, _Building Microservices_** — "premature decomposition" is a named
  anti-pattern; split only on boundaries that have proven stable.

## Consequences

- The scaffold ships one worked bounded context; docs must explain how to add
  another (package first → Maven module when stable). This resolves the
  bounded-context part of `design-00001` Q3.
- A dependency on Spring Modulith (`spring-modulith-starter-jpa`) is kept for
  `verify()` + the event publication registry, even though layers are Maven
  modules and Maven is the boundary mechanism for the worked context.
- Enforcement asymmetry to accept: inside a not-yet-promoted (packaged) context,
  layer isolation is test-time (ArchUnit), not compile-time, until the context is
  promoted to the five-module structure.
- The `*-api` contract + integration-event types are the stable interface across
  all three promotion stages, so evolution never forces a domain rewrite.
- `design-00001` should link here for the bounded-context realization strategy;
  its layout section keeps the layer/module mechanics.

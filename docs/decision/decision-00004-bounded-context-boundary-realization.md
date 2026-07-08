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

## The three candidate structures (catalog + ordering)

Legend: a directory that is a Maven module (its own `pom.xml` / jar) is marked
**`[module]`**; everything else is a plain Java package.

### Structure 1 — Logical (packages + Spring Modulith). DEFAULT for a young/uncertain BC.

One Maven module; each BC is a top-level package; layers are sub-packages.

```
app/                                    [module]  one jar
├── pom.xml
└── src/main/java/com/acme/
    ├── AcmeApplication.java                      @SpringBootApplication
    ├── catalog/                                  BC = package  (Spring Modulith module)
    │   ├── package-info.java                     @ApplicationModule
    │   ├── BookAddedToCatalog.java               published event (base pkg = public API)
    │   ├── domain/                               layer = sub-package (ArchUnit)
    │   ├── application/
    │   ├── infrastructure/
    │   └── adapter/
    └── ordering/                                 BC = package
        ├── package-info.java
        ├── OrderPlaced.java
        ├── domain/  application/  infrastructure/  adapter/
```

- Maven modules: **1**. BC boundary and layer boundary are both **test-time**
  (Modulith `verify()` + ArchUnit). Move a boundary = rename a package (cheap).

### Structure 2 — Global layer modules ("layer = module, BC = package"). REJECTED.

Layers are Maven modules; each holds *both* BCs' code for that layer.

```
app/                                    aggregator POM
├── domain/                             [module]  ALL BCs' domain in ONE jar
│   └── com/acme/
│       ├── catalog/domain/...                    catalog + ordering domain share a jar →
│       └── ordering/domain/...                   they can reference each other AT COMPILE TIME
├── application/                        [module]  com/acme/{catalog,ordering}/application/...
├── infrastructure/                     [module]  com/acme/{catalog,ordering}/infrastructure/...
├── adapter/                            [module]  com/acme/{catalog,ordering}/adapter/...
└── start/                              [module]  @SpringBootApplication
```

- Compile-time isolation is between **layers**, not BCs → `catalog.domain` can
  import `ordering.domain` (same jar); BC isolation drops to Modulith test-time.
- Extracting `catalog` means carving it out of **every** layer jar.
- Strong guarantee on the low-value axis (layer), weak on the high-value axis
  (BC). **This is the "layer = module while BC = package" combination — and it is
  backwards, so it is rejected.**

### Structure 3 — Physical (per-context Maven modules, both axes). The PROMOTED state.

Each BC is an aggregator of five layer modules; cross-BC only via `*-api`.

```
app/                                    aggregator POM
├── shared-kernel/                      [module]
├── catalog/                            aggregator for the catalog BC
│   ├── catalog-api/                    [module]  integration events + public contract
│   ├── catalog-domain/                 [module]  framework-free (no Spring/JPA on classpath)
│   ├── catalog-application/            [module]  → catalog-domain, catalog-api
│   ├── catalog-infrastructure/         [module]  → catalog-application, -domain, -api
│   └── catalog-adapter/                [module]  → catalog-application, -api
├── ordering/                           aggregator for the ordering BC
│   ├── ordering-api/                   [module]
│   ├── ordering-domain/                [module]
│   ├── ordering-application/           [module]
│   ├── ordering-infrastructure/        [module]  may depend on catalog-api ONLY
│   └── ordering-adapter/               [module]
└── start/                              [module]  @SpringBootApplication → every ctx adapter+infra
```

- Both axes **compile-time**: `catalog-domain`'s classpath has no `ordering-*` (cross-BC
  leak won't compile) and no Spring/JPA (framework leak won't compile).
- Extraction: `catalog/` is already self-contained → lift the folder out, swap
  in-process events for a broker. Cross-BC coupling is only `catalog-api`.

## Decision

- **Physical vs logical is chosen *per whole bounded context*; a context's two
  axes move together.** A context is either **logical** (Structure 1: BC = package
  via `verify()`, layers = sub-packages via ArchUnit) or **physical** (Structure 3:
  BC = Maven aggregator, layers = Maven modules). "Layer is a Maven module while
  the BC is only a package" is **not** a valid state for one context — the only way
  to force it is Structure 2, which is rejected.
- **Reject Structure 2** (global layer modules): it puts compile-time isolation on
  the low-value axis (layer), leaves BC isolation test-time only, and makes
  extraction cross-cutting surgery. (It is fine only when there is a single BC, where
  Structures 2 and 3 coincide — e.g. a COLA single-service app.)
- **Default = logical (Structure 1).** New or not-yet-understood contexts start
  here, because reshaping a boundary is a package refactor.
- **Promote to physical (Structure 3)** when the boundary is stable *and* the
  context needs independent build/deploy/scaling — and later, if warranted, its
  own service.
- **The template ships one context already promoted (Structure 3)** as the worked
  example; additional contexts start logical (Structure 1).
- **Cross-context communication is always via published language** (integration
  events / the `*-api` contract), never a direct internal reference — *regardless*
  of whether the context is a package or a module. This makes promotion a
  packaging/transport change, not a rewrite.
- **Spring Modulith's role is therefore scoped to:** (a) enforcing logical BC
  boundaries in Structure 1; (b) its event publication registry (the transactional
  outbox) for inter-context events, in any structure; (c) easing extraction via
  `@Externalized`. It is **not** the layer-boundary mechanism — Maven modules are.

Promotion path (contract stable throughout): **Structure 1 (package BC)** →
**Structure 3 (Maven-module BC)** → **separate service** (in-process events →
broker; `*-api` facade call → HTTP/RPC). Structure 2 is never a target.

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

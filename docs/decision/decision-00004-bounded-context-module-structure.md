---
id: decision-00004-bounded-context-module-structure
type: decision
role: main
status: draft
parent:
---

# How bounded contexts and layers map to modules

Draft for review. Every claim below is backed either by a distilled note in
`docs/reference/` or by an external source listed under **Sources**; nothing here
is asserted without one.

## Context

A bounded context (BC) is a *language/model* boundary — the scope in which one
ubiquitous language and model are consistent — **not** a deployment unit; this is
the DDD position (Evans, Vernon), summarized in
`docs/reference/domain-driven-hexagon/`. A microservice is a *deployment* unit.
The two often align but are different axes.

A DDD codebase decomposes on two axes — by BC (vertical) and by layer
(domain/application/infrastructure/adapter/api, horizontal). The question this
ADR settles: **how do BCs and layers become modules, and when?** There are three
viable structures (plus one rejected combination), differing in *how many
deployables* and *whether each boundary is enforced at compile time or test
time*.

Guiding principle from industry practice — **start modular, not distributed**:

- Martin Fowler, *MonolithFirst*: "you shouldn't start a new project with
  microservices, even if you're sure your application will be big enough";
  "almost all the successful microservice stories have started with a monolith
  that got too big and was broken up," whereas systems "built as a microservice
  system from scratch … ended up in serious trouble." Microservices need "good,
  stable boundaries … the task of drawing up the right set of BoundedContexts,"
  and "even experienced architects … have great difficulty getting boundaries
  right at the beginning."
- Sam Newman (QCon, InfoQ): a "monolith" is a *unit of deployment*, not "legacy";
  the **modular monolith** — one process of independently-workable modules — is
  "a good, often overlooked choice (Shopify is a good example)"; premature
  decomposition into microservices is costly, especially before the domain is
  understood.
- Counterpoint for honesty — Stefan Tilkov, *Don't start with a monolith*: for
  some greenfield systems with known boundaries, starting distributed is
  defensible. We treat monolith-first as the **default**, not a law.

## The three structures (catalog + ordering)

`[module]` = a Maven module (own `pom.xml`/jar); everything else is a Java
package.

### Structure 1 — Modular monolith, **logical** BCs (Spring Modulith)

One deployable. Each BC is a top-level package; Spring Modulith treats every
direct sub-package of the application root as a module and verifies the
boundaries; layers are sub-packages checked by ArchUnit/jMolecules.

```
app/                                   [module]  one deployable jar
└── com/acme/
    ├── AcmeApplication.java                     @SpringBootApplication
    ├── catalog/                                 BC = package (Spring Modulith module)
    │   ├── package-info.java                    @ApplicationModule
    │   ├── BookAddedToCatalog.java              published API (base package)
    │   ├── domain/ application/ infrastructure/ adapter/   layers = sub-packages
    └── ordering/                                BC = package
        ├── OrderPlaced.java
        └── domain/ application/ infrastructure/ adapter/
```

- Boundaries: **BC and layer both test-time** (Spring Modulith
  `ApplicationModules.verify()`, backed by ArchUnit; Spring Modulith 2.0 can also
  check at startup). Moving a boundary = renaming a package (cheap).
- Basis: Spring Modulith docs (module = direct sub-package; `verify()`);
  `docs/reference/spring-modulith-with-ddd/`; Newman (modular monolith).

### Structure 2 — Modular monolith, **physical** BCs (multi-module / B2)

One deployable, assembled by `start/`. Each BC is a Maven aggregator of
per-context layer modules; cross-BC references only to another context's `*-api`.

```
app/                          aggregator POM (one deployable via start/)
├── shared-kernel/            [module]
├── catalog/                  aggregator for the catalog BC
│   ├── catalog-api/          [module]  integration events + public contract
│   ├── catalog-domain/       [module]  framework-free (no Spring/JPA on classpath)
│   ├── catalog-application/  [module]
│   ├── catalog-infrastructure/ [module]
│   └── catalog-adapter/      [module]
├── ordering/                 aggregator → ordering-{api,domain,application,infrastructure,adapter} [modules]
└── start/                    [module]  @SpringBootApplication, assembles all BCs
```

- Boundaries: **BC and layer both compile-time** (a disallowed type is off the
  classpath). Moving a boundary = Maven surgery (expensive).
- Basis: `docs/reference/ddd-by-examples-factory/` (Gradle module per context —
  `*-model`/`*-adapters`); `docs/reference/modular-monolith-with-ddd/` (module =
  bounded context, schema-per-module, extract with no app change); layer names
  follow COLA (adapter/app/domain/infrastructure).

### Structure 3 — **Service per BC** (COLA per service; microservices)

N deployables — one per BC, each its own repo/Spring Boot app, internally layered
COLA-style (`adapter` / `app` / `client` / `domain` / `infrastructure` +
`start`). BCs talk over the network (HTTP/RPC + broker), never in-process.

```
catalog-service/   (own repo + deployable = one BC)
└── catalog-{adapter,app,client,domain,infrastructure} [modules] + catalog-start [module]

ordering-service/  (separate repo + deployable = another BC)
└── ordering-{adapter,app,client,domain,infrastructure} [modules] + ordering-start [module]

# catalog ↔ ordering communicate over HTTP/RPC/broker, not in one process
```

- Basis: `github.com/alibaba/COLA` — COLA is a layered architecture for **one
  application/service** (archetype "divides by domain"; layers adapter / app /
  client(API) / domain / infrastructure + a bootstrap `start`). A second BC is a
  second COLA service. This is the microservices end-state.

### Rejected combination — global layer modules ("layer = module, BC = package")

One deployable; layers are *global* Maven modules; both BCs live as packages
inside each layer jar.

```
app/                          aggregator POM, one deployable
├── domain/          [module]  com/acme/catalog/... AND com/acme/ordering/...  (shared jar)
├── application/     [module]  both BCs
├── infrastructure/  [module]  both BCs
├── adapter/         [module]  both BCs
└── start/           [module]
```

- Rejected: compile-time isolation lands on the **layer** axis, so
  `catalog.domain` can import `ordering.domain` (same jar) — **BC isolation drops
  to test-time**, and extracting a BC means carving it out of every layer jar.
  This puts the strong guarantee on the low-value axis. It is acceptable **only**
  in the degenerate single-BC case, where it collapses into Structure 3's
  per-service COLA layout.
- Basis: BC is the boundary that matters most (Fowler: boundaries =
  BoundedContexts; `docs/reference/domain-driven-hexagon/`); the whole point of
  monolith-first is cheap BC extraction (Newman) — this structure makes it
  cross-cutting.

## Decision

1. **Default to a modular monolith, not service-per-BC.** New systems start at
   Structure 1 or 2 (one deployable), never Structure 3, because stable BC
   boundaries are hard to get right upfront and premature decomposition is costly
   (Fowler, Newman).
2. **Physical vs logical is chosen per whole bounded context; its two axes move
   together.** A context is either Structure 1 (logical: BC = package, layers =
   sub-packages) or Structure 2 (physical: BC = aggregator, layers = modules).
   "Layer is a module while the BC is only a package" is **not** a valid state —
   the only way to force it is the rejected global-layer-modules combination.
3. **Promotion ladder, contract stable throughout:** Structure 1 (package BC) →
   Structure 2 (Maven-module BC, when the boundary is stable and wants
   compile-time isolation / independent build) → Structure 3 (own service, when
   it needs independent deploy/scale/ownership). The `*-api` + integration-event
   contract is unchanged across all three; only packaging/transport changes.
4. **Reject global layer modules** for any multi-BC build (see above).
5. **Cross-context communication is always via published language** (integration
   events / `*-api`), never a direct internal reference — in every structure.
   Basis: `docs/reference/modular-monolith-with-ddd/`,
   `docs/reference/spring-modulith-with-ddd/`.
6. **Spring Modulith's scope:** boundary verification in Structure 1; its event
   publication registry (transactional outbox) for inter-context events in
   Structures 1–2; extraction aid via `@Externalized` toward Structure 3. It is
   **not** the layer-boundary mechanism in Structure 2 (Maven is). Basis: Spring
   Modulith docs; `docs/reference/spring-modulith-with-ddd/`.

Open for review: **which structure does the template ship the one worked BC in —
Structure 1 (simplest, monolith-first-purest) or Structure 2 (shows the full
target module layout)?** Recommendation: Structure 2 for the worked context (it
is the reference the template exists to demonstrate), with added contexts
starting at Structure 1.

## Consequences

- The scaffold ships one worked BC; docs explain adding another (Structure 1
  package first → promote when justified).
- Keep a `spring-modulith-starter-jpa` dependency for the event publication
  registry even under Structure 2.
- In Structure 1, layer isolation for a context is test-time (ArchUnit) until it
  is promoted to Structure 2.
- The Structure 2 module mechanics live in this ADR (the Structure 2 tree above);
  a future design doc, if one is written, can reference this ADR rather than
  restating it.

## Sources

Internal (distilled, in `docs/reference/`):
`modular-monolith-with-ddd/`, `spring-modulith-with-ddd/`,
`ddd-by-examples-factory/`, `domain-driven-hexagon/`.

External:

- Martin Fowler, *MonolithFirst* — https://martinfowler.com/bliki/MonolithFirst.html
- Stefan Tilkov, *Don't start with a monolith* (counterpoint) — https://martinfowler.com/articles/dont-start-monolith.html
- Sam Newman at QCon London, "Decomposing a Monolith Does Not Require Microservices" (InfoQ) — https://www.infoq.com/news/2020/05/monolith-decomposition-newman/
- Spring Modulith — Fundamentals — https://docs.spring.io/spring-modulith/reference/fundamentals.html
- Spring Modulith — Verifying Application Module Structure — https://docs.spring.io/spring-modulith/reference/verification.html
- Alibaba COLA (Clean Object-oriented & Layered Architecture) — https://github.com/alibaba/COLA

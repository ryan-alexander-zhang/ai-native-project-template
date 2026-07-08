# Third-Party References

External code and specs used **for reference only** — not vendored into this
repo, not a build dependency. Keep clones **outside** the repo so they do not
pollute version control. Distilled notes live under `docs/reference/<slug>/`.

Add one section per third-party reference. Record only what a contributor needs
to find and pin the source; the working notes live under `docs/reference/`.

The DDD references below back the Java DDD template on this branch
(`lang/java/ddd`). Recommended reading order: library → factory → jmolecules →
spring-modulith-with-ddd → modular-monolith-with-ddd. Star counts and pinned
commits verified 2026-07-08.

## ddd-by-examples/library

| | |
| --- | --- |
| Upstream | https://github.com/ddd-by-examples/library |
| Pinned commit | `98d7004b5aef366ba5d661ae0cb544af1cfd33f1` (2022-05-25) |
| License | MIT · Java · 5.8k★ |
| Local clone | `../ddd-refs/library` (outside this repo) |
| Official docs | `docs/big-picture.md`, `docs/example-mapping.md`, `docs/design-level.md` (in-repo) |
| Distilled excerpts | [`docs/reference/ddd-by-examples-library/`](docs/reference/ddd-by-examples-library/) |

Library lending system: DDD end-to-end from Event Storming to code —
policy/specification objects, `Either`-returning aggregates, CQRS-lite read
models, store-and-forward outbox, ArchUnit boundary rules.

```bash
git clone https://github.com/ddd-by-examples/library ../ddd-refs/library
git -C ../ddd-refs/library checkout 98d7004b5aef366ba5d661ae0cb544af1cfd33f1
```

## ddd-by-examples/factory

| | |
| --- | --- |
| Upstream | https://github.com/ddd-by-examples/factory |
| Pinned commit | `95c751ccefb879e02ecc959c712caa31f4cd9bcf` (2021-04-24) |
| License | MIT · Java · 1.4k★ |
| Local clone | `../ddd-refs/factory` (outside this repo) |
| Official docs | none external — README + embedded Event Storming boards |
| Distilled excerpts | [`docs/reference/ddd-by-examples-factory/`](docs/reference/ddd-by-examples-factory/) |

Manufacturing-factory domain on a Gradle multi-module modular monolith:
framework-free `*-model` vs `*-adapters`, selective hexagon, "three lanes"
(CRUD / commands / queries), domain-event ports.

```bash
git clone https://github.com/ddd-by-examples/factory ../ddd-refs/factory
git -C ../ddd-refs/factory checkout 95c751ccefb879e02ecc959c712caa31f4cd9bcf
```

## xmolecules/jmolecules

| | |
| --- | --- |
| Upstream | https://github.com/xmolecules/jmolecules |
| Pinned commit | `20d2636beba2e3cb8f0a9b62c07e3606c49da84f` (2025-11-20) |
| License | Apache-2.0 · Java · 1.5k★ |
| Local clone | `../ddd-refs/jmolecules` (outside this repo) |
| Official docs | https://jmolecules.org · coordinates `org.jmolecules` |
| Distilled excerpts | [`docs/reference/jmolecules/`](docs/reference/jmolecules/) |

DDD-as-code: annotations + type interfaces (`@AggregateRoot`, `@ValueObject`,
`Association<T,ID>`) with ByteBuddy/Spring/JPA/Jackson integrations and ArchUnit
rules. A build dependency candidate for the template, not just reference.

```bash
git clone https://github.com/xmolecules/jmolecules ../ddd-refs/jmolecules
git -C ../ddd-refs/jmolecules checkout 20d2636beba2e3cb8f0a9b62c07e3606c49da84f
```

## xsreality/spring-modulith-with-ddd

| | |
| --- | --- |
| Upstream | https://github.com/xsreality/spring-modulith-with-ddd |
| Pinned commit | `bbbabe06d3af9ddd78c5699ca7b4dab36792ee8c` (2026-05-14) |
| License | MIT · Java · 279★ |
| Local clone | `../ddd-refs/spring-modulith-with-ddd` (outside this repo) |
| Official docs | itnext.io blog series (see reference notes) |
| Distilled excerpts | [`docs/reference/spring-modulith-with-ddd/`](docs/reference/spring-modulith-with-ddd/) |

Closest match to the target stack: Spring Boot 4 / Spring Modulith 2 +
jMolecules modular monolith. `ApplicationModules.verify()`,
`@ApplicationModuleListener` + JPA event registry, `@ApplicationModuleTest`
`Scenario` API, generated C4 docs.

```bash
git clone https://github.com/xsreality/spring-modulith-with-ddd ../ddd-refs/spring-modulith-with-ddd
git -C ../ddd-refs/spring-modulith-with-ddd checkout bbbabe06d3af9ddd78c5699ca7b4dab36792ee8c
```

## AxonIQ/AxonFramework

| | |
| --- | --- |
| Upstream | https://github.com/AxonIQ/AxonFramework |
| Pinned commit | `120cca303b0b2bc4d74d81c75fbb78e13fe84138` (2026-07-08) |
| License | Apache-2.0 · Java · 3.6k★ |
| Local clone | `../ddd-refs/AxonFramework` (outside this repo) |
| Official docs | https://docs.axoniq.io |
| Distilled excerpts | [`docs/reference/axon-framework/`](docs/reference/axon-framework/) |

CQRS/Event-Sourcing framework: aggregate programming model, command/event/query
buses, projections, sagas. Reference for the *patterns*; see the "when not to
adopt" caveats before pulling in the whole framework.

```bash
git clone https://github.com/AxonIQ/AxonFramework ../ddd-refs/AxonFramework
git -C ../ddd-refs/AxonFramework checkout 120cca303b0b2bc4d74d81c75fbb78e13fe84138
```

## kgrzybek/modular-monolith-with-ddd

| | |
| --- | --- |
| Upstream | https://github.com/kgrzybek/modular-monolith-with-ddd |
| Pinned commit | `91c8ef24b4cb6ef558c95d8267fa07d68c7059f8` (2024-04-20) |
| License | MIT · C# · 13.8k★ |
| Local clone | `../ddd-refs/modular-monolith-with-ddd` (outside this repo) |
| Official docs | kamilgrzybek.com (modular-monolith-primer, outbox-pattern) |
| Distilled excerpts | [`docs/reference/modular-monolith-with-ddd/`](docs/reference/modular-monolith-with-ddd/) |

.NET reference (patterns mapped to Java): module-per-context with a single
facade, schema-per-module, command/query bus with a Logging→Validation→UnitOfWork
decorator chain, `BusinessRule`/`checkRule`, transactional Outbox + Inbox.

```bash
git clone https://github.com/kgrzybek/modular-monolith-with-ddd ../ddd-refs/modular-monolith-with-ddd
git -C ../ddd-refs/modular-monolith-with-ddd checkout 91c8ef24b4cb6ef558c95d8267fa07d68c7059f8
```

## Sairyss/domain-driven-hexagon

| | |
| --- | --- |
| Upstream | https://github.com/Sairyss/domain-driven-hexagon |
| Pinned commit | `5c2d15a7e2d69e83dfddf28468ee9f30e02c30de` (2024-05-14) |
| License | MIT · TypeScript · 14.8k★ |
| Local clone | `../ddd-refs/domain-driven-hexagon` (outside this repo) |
| Official docs | the README is the reference article |
| Distilled excerpts | [`docs/reference/domain-driven-hexagon/`](docs/reference/domain-driven-hexagon/) |

TypeScript/NestJS tutorial (vocabulary mapped to Java): Explicit Architecture,
building-block responsibilities, vertical-slice-per-use-case, validate-vs-guard,
always-valid domain model, typed-result error handling.

```bash
git clone https://github.com/Sairyss/domain-driven-hexagon ../ddd-refs/domain-driven-hexagon
git -C ../ddd-refs/domain-driven-hexagon checkout 5c2d15a7e2d69e83dfddf28468ee9f30e02c30de
```

## ardalis/CleanArchitecture

| | |
| --- | --- |
| Upstream | https://github.com/ardalis/CleanArchitecture |
| Pinned commit | `4f80d58ddb3eb74c6ec8fcad0394e811d01106f5` (2026-06-17) |
| License | MIT · C# · 18.3k★ |
| Local clone | `../ddd-refs/CleanArchitecture` (outside this repo) |
| Official docs | https://ardalis.github.io/CleanArchitecture |
| Distilled excerpts | [`docs/reference/clean-architecture/`](docs/reference/clean-architecture/) |

.NET Clean Architecture template (patterns mapped to Java): dependency-rule
project layout, explicit use-case layer, Result-as-value, repository-per-aggregate
ports, after-commit event dispatch. Reference for the **boundary skeleton** — a
single-context CRUD template, so it underserves tactical DDD (anemic model, no
modules, no outbox); add that richness yourself.

```bash
git clone https://github.com/ardalis/CleanArchitecture ../ddd-refs/CleanArchitecture
git -C ../ddd-refs/CleanArchitecture checkout 4f80d58ddb3eb74c6ec8fcad0394e811d01106f5
```

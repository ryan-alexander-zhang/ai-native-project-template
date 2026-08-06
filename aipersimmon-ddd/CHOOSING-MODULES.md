# Choosing modules

Start from a bundle. Only take modules one at a time when a bundle brings something you do not want —
and read [When to pick modules by hand](#when-to-pick-modules-by-hand) before you do, because most
reasons for wanting to turn out to be configuration questions, not dependency questions.

## The short answer

```mermaid
flowchart TD
    A["Does this service own a database?"] -->|no| B["aipersimmon-ddd-starter"]
    A -->|yes| D["aipersimmon-ddd-starter-mybatis-plus"]
    D --> F{"Do events cross a service boundary?"}
    B --> F
    F -->|"no, one deployable"| G["done"]
    F -->|yes| H["+ aipersimmon-ddd-starter-messaging-kafka"]
    H --> I{"Serving an HTTP API you document?"}
    G --> I
    I -->|yes| J["+ aipersimmon-ddd-openapi-spring-boot-starter"]
    I -->|no| K{"Exporting traces?"}
    J --> K
    K -->|yes| L["+ aipersimmon-ddd-observability-otel-spring-boot-starter"]
    K -->|no| M["done"]
    L --> M
```

Four dependencies is a normal, complete service. The scaffold uses exactly those four.

## Question by question

### "Does this service own a database?"

If it does not — a pure API gateway, a read-only projection reading someone else's store, a
calculation service — take **`aipersimmon-ddd-starter`**. You get the command and query buses, the
interceptor chain, in-process events, time-ordered ids and the RFC 9457 web contract, and no tables.

Everything below assumes a database, because the outbox, the inbox, the process manager and the
operation log are all "state that survives a restart".

### "Which data-access stack?"

There is one: **`aipersimmon-ddd-starter-mybatis-plus`**. Every storage component — versioned
repositories, outbox, inbox, process manager, operation log — has exactly one backend, and it is
MyBatis-Plus. That is deliberate: the framework's tables and your aggregates must share **one**
`SqlSessionFactory`, `DataSource` and transaction, so that an aggregate write and its outbox write
commit together — the whole point of a transactional outbox — and a second backend to keep in step
bought nothing but a second place for the two to drift apart.

It also gives you SQL-level tenant scoping, because tenant rewriting is a MyBatis-Plus interceptor
capability.

Your own tables need not go through MyBatis-Plus: a `JdbcTemplate`, a hand-written mapper or anything
else on the same `DataSource` enlists in the same transaction. The requirement is the shared
`DataSource`, not a shared style.

### "Do events cross a service boundary?"

Only add **`aipersimmon-ddd-starter-messaging-kafka`** when another *deployable* must react. Inside one
deployable, integration events are already delivered in process, synchronously, in the publisher's
transaction — no broker, no inbox, no eventual consistency to explain to anyone.

When you do add it, two things change:

- Events annotated `@Externalized("topic")` go to the broker and come back through the consumer bridge
  to the same local `@EventListener` handlers, so handler code does not change. Events without the
  annotation stay in process.
- The transport is built **on** the outbox: a storage bundle is a prerequisite, not an alternative. If
  none is present, startup fails rather than letting `@Externalized` events be published in process
  and silently never leave the JVM.

### The two add-ons

Neither is bundled, because each pulls an opinionated third-party stack that should be a choice:

- **`aipersimmon-ddd-openapi-spring-boot-starter`** — springdoc plus a customizer that documents the
  problem responses the web layer actually emits, so the spec matches the behaviour.
- **`aipersimmon-ddd-observability-otel-spring-boot-starter`** — binds the framework's observability
  SPIs to OpenTelemetry and pulls in boundary auto-instrumentation. Without it those SPIs are present
  and no-op, so the domain spine costs nothing when you are not exporting traces.

### Testing

| | |
| --- | --- |
| `aipersimmon-ddd-archunit` (test) | the layering and building-block rules as runnable tests over *your* code |
| `aipersimmon-ddd-test-support` (test) | singleton Testcontainers + `@ServiceConnection` configs, so integration tests share one container |

## When to pick modules by hand

Bundles aggregate; they never restrict. Every module inside one stays independently usable, and every
bean stays `@ConditionalOnMissingBean`. Reach for individual modules when:

- **You want one component and nothing else.** A service that needs only the command bus and the
  outbox takes `aipersimmon-ddd-cqrs-spring-boot-starter` + `aipersimmon-ddd-outbox-mybatis-plus`.
- **A domain or application module must compile without Spring.** Depend on the contract modules
  (`-core`, `-cqrs`, `-integration`, `-application`, `-tenancy`, `-inbox`, `-outbox`, `-process-manager`,
  `-operation-log`, `-web`). None of them names a framework, and a build-time check keeps it that way,
  so your domain layer can depend on them without inheriting Spring.
- **You are replacing a seam.** Bring the contract module and your own implementation.

Reasons that are **not** module questions:

| You are thinking | The actual lever |
| --- | --- |
| "I don't want tenancy yet" | it is inert until `aipersimmon.ddd.tenancy.enabled=true` |
| "I don't want these tables" | `aipersimmon.ddd.flyway.components` — listing nothing creates nothing |
| "I don't want a background poller" | each has its own `enabled` / `poll-delay` |
| "I don't want the audit log everywhere" | it records only commands carrying `@OperationLog` |

Bundling is not enabling. Adding a bundle costs you jar size and nothing else until you configure
something.

## Reading the names

| Shape | What it is | May depend on a framework | Safe for a domain module |
| --- | --- | --- | --- |
| `aipersimmon-ddd-starter[-<stack>]` | a **bundle**: aggregates other modules | yes | no |
| `aipersimmon-ddd-<concern>` | a **contract**: ports, value objects, state machines | **no** | **yes** |
| `aipersimmon-ddd-<concern>-<backend>` | an **adapter** for `mybatis-plus` / `redis` / `kafka` | yes | no |
| `aipersimmon-ddd-<concern>-engine` | a **storage-agnostic runtime** shared by backends | yes | no |
| `aipersimmon-ddd-<concern>-spring-boot-starter` | **wiring** for one concern | yes | no |

The distinction that matters is column three: if the name has no technology suffix, the module is
framework-free and your domain layer may name it. That is enforced by a build-time check, not by
convention alone.

# aipersimmon-ddd-flyway (shared, Scheme B)

Opt-in, schema-agnostic Flyway integration for the whole aipersimmon-ddd family. Add this one
module and the tables of whichever aipersimmon storage modules you use — outbox, inbox,
web-store, saga, process-manager — apply themselves at startup. **No manual SQL, no
`spring.flyway.locations`, no per-module Flyway starter.**

## Use it

```xml
<dependency>
  <groupId>com.aipersimmon.ddd</groupId>
  <artifactId>aipersimmon-ddd-flyway</artifactId>
</dependency>
```

H2 works out of the box (bundled in `flyway-core`). For PostgreSQL / MySQL also add the
matching Flyway database module:

```xml
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
```

On startup you'll see one line per discovered component, e.g.:

```
aipersimmon-ddd Flyway: component 'outbox' applied 1 migration(s) from classpath:aipersimmon/db/migration/outbox/h2 via history table 'flyway_schema_history_aipersimmon_outbox'
```

## How it works

- Each storage module ships its own migrations at
  `classpath:aipersimmon/db/migration/{component}/{vendor}` (the single source of that
  component's schema — also what a non-Flyway consumer copies into Liquibase or runs by hand).
  They live **outside** `db/migration` on purpose, so Spring Boot's default Flyway never scans
  them (which would trip over the many per-component `V1`s).
- It plugs into Spring Boot's **own** default Flyway through a `FlywayMigrationStrategy` — it does
  not run a competing initializer. Boot's single `flywayInitializer` calls the strategy, which:
  1. runs **your** own `classpath:db/migration` migrations first (Boot-configured, your default
     `flyway_schema_history`, your business tables) — untouched;
  2. then resolves the DB vendor from the `DataSource` and applies each aipersimmon component into
     its **own** history table `flyway_schema_history_aipersimmon_{component}`, so nothing shares a
     version space with your migrations, nor with each other.
- Because everything runs inside Boot's one initializer, any `@DependsOnDatabaseInitialization` bean
  (such as the process-manager schema validator) already waits until every table exists — no
  bean-ordering games, no circular dependencies, and your `spring.flyway.*` config is honored.
- Requires Boot's Flyway auto-config to be on (the default). If you define your own
  `FlywayMigrationStrategy`, this one backs off — then call `AipersimmonFlywayMigrator.migrate(..)`
  from yours.

## Configuration (`aipersimmon.ddd.flyway.*`)

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Set `false` to manage the schema yourself. |
| `components` | *(empty = all)* | Restrict to named components, e.g. `outbox,inbox`. |
| `baseline-on-migrate` | `true` | Adopt onto a non-empty existing database. |
| `baseline-version` | `0` | Must sort below `V1` or the first migration would be skipped. |
| `history-table-prefix` | `flyway_schema_history_aipersimmon_` | Per-component history table prefix. |

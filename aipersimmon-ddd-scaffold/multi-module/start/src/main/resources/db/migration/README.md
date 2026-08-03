# Migrations, one directory per bounded context

Flyway scans `classpath:db/migration` **recursively**, so these subdirectories need no configuration
— `spring.flyway.locations` still names the parent. What the layout buys is not a Flyway feature; it
is the answer to a question the old flat layout could not answer: *which migrations belong to which
context?*

## The version namespace

Each context owns a major version, and numbers within it are its own sequence:

| Directory | Versions | Owns |
|---|---|---|
| `ordering/` | `1.1` … `1.6` | schema `ordering`: `customers`, `orders`, `order_lines` |
| `inventory/` | `2.1` … `2.4` | schema `inventory`: `stocks`, `reservations`, `reservation_lines` |
| `payment/` | `3.1` | schema `payment`: `payment_operations` |

Flyway merges every location into one history and applies them in version order, so the global run
order is `1.1, 1.2, … 1.6, 2.1, … 2.4, 3.1`. **Interleaving is not required and would not help**: no
foreign key, view or join crosses a context boundary, so inventory's tables have no opinion about
whether ordering's exist yet. Within a context the order absolutely matters, and that is exactly what
the minor number expresses.

Framework tables (outbox, inbox, process manager, operation log, web store) are not here at all. They
are applied by the library from `aipersimmon.ddd.flyway.components`, each under its own history table
(`flyway_schema_history_aipersimmon_<component>`).

## Why this beats the flat layout it replaced

It used to be `V1__aggregates.sql` … `V7__order_total.sql`, ordered by *when someone thought of it*.
Four of those seven files touched two schemas at once — `V1` created both, `V2` added `tenant_id` to
both, `V3` added `version` to both, `V4` reworked keys and indexes in both. That organisation had two
costs, and only the second is obvious in hindsight:

1. **You could not tell what a context owned.** Answering "what is inventory's schema?" meant reading
   all seven files and mentally filtering. Now it is `ls inventory/`.
2. **Extracting a context meant splitting the migration history.** The scaffold's own README describes
   the messaging boundaries as clean enough to pull a context into its own service — and they are:
   Kafka topics, `*-api` contracts, and a port for the one synchronous call. But the DDL was welded
   together. Extracting inventory from the flat layout required opening four files, separating the
   statements by schema, renumbering, and reconciling `flyway_schema_history` on a live database.
   From here it is: take `inventory/`, take the history rows whose version starts with `2.`, done.

The second cost is the kind that is free to avoid on day one and expensive to fix on day nine
hundred. Nothing about the flat layout was wrong while there was one deployable; it was simply a
decision left un-made, and it defaulted to the shape that is hardest to undo.

## Rules for adding one

- **Put it in your context's directory and use your context's major.** A migration that needs to touch
  two schemas is telling you something — usually that a table is in the wrong context, occasionally
  that you want a genuinely shared concern, which belongs in neither of these and probably belongs to
  a library component.
- **Structure only, never data.** A versioned migration runs exactly once in *every* environment and
  Flyway offers no way to opt out per profile, so seed rows placed here reach production too. Demo
  data lives in `db/dev/afterMigrate__seed.sql`, a location only the dev profile loads.
  `MigrationContentTest` walks this whole tree and fails on an `INSERT INTO`.
- **A new context gets a new major**, a new directory, and its own `CREATE SCHEMA IF NOT EXISTS`.

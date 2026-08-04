# S23 — Changing a table that already has data in it

Two bounded contexts share one database, the framework has migrations of its own against the same
schema, and one table's shape changed three times after it went live. That is the whole sample.

Companion document: `docs/analysis/analysis-00036-samples-schema-migration.md`.

## Run it

```bash
mvn -pl s23-schema-migration -am verify    # 26 tests
```

Real PostgreSQL via Testcontainers; the tests **skip** rather than fail without Docker. No Kafka: this
sample's events stay LOCAL, and the outbox is here as the place a backfill's announcements land.

## Three sets of migrations, one database

| Owner | Location | History table | Run by |
| --- | --- | --- | --- |
| ordering | `db/migration/ordering` | `flyway_schema_history_ordering` | Boot's own Flyway (`spring.flyway.*`) |
| billing | `db/migration/billing` | `flyway_schema_history_billing` | `MigrationConfiguration`, second |
| the framework | inside the aipersimmon jars | `flyway_schema_history_aipersimmon_outbox` | `MigrationConfiguration`, last |

Both contexts have a **V1**, and that is the point. Their version numbers were assigned by different
people for unrelated reasons, so there is no answer to "which V1 comes first" — and Flyway agrees:
pointed at their common parent it refuses outright with *"more than one migration with version 1"*
(measured). One shared version space would make every schema change a negotiation and a cherry-picked
release impossible to apply.

Nobody owns the unnamed default `flyway_schema_history`. Left to the first context — which always gets
it, because it was alone — the second context arrives to find the obvious name taken and the first
context's history indistinguishable from "the application's".

### The trap a second context walks you into

The library normally installs its own `FlywayMigrationStrategy`, which runs your migrations and then the
framework's components. That bean is `@ConditionalOnMissingBean`. **A second context forces you to define
a strategy, and the moment you do, the library's backs off and takes the component migrations with it.**

Nothing is announced at the point the mistake is made — from the library's side, a consumer who supplied
a strategy has taken over the job, which is a fair reading. It is caught one bean later, by the outbox's
schema validator refusing to start. So the floor is a failed boot rather than a production database
missing tables.

But **the message points at the wrong fix**: it says to add `outbox` to
`aipersimmon.ddd.flyway.components`, which in this application is already there. The cause is the
uncalled migrator. Measured in `StrategyTrapTest`; the habit that prevents it is that the last line of
any `FlywayMigrationStrategy` you write is a call to `AipersimmonFlywayMigrator.migrate`.

And only the framework components ship a validator. A **context of your own** that quietly failed to
migrate leaves an application that starts, serves ordering perfectly, and 500s the first time anyone
touches billing.

### The asymmetry nobody predicts: a baseline row

Ordering's history holds versions `1, 2, 3, 4`. Billing's holds `0, 1` — a **baseline at version 0**,
because by the time it runs, ordering's tables exist and the schema is not empty. Which is why the
baseline version has to be `0`: at `1` it would mark billing's V1 as already applied and `s23_invoice`
would never be created, silently, on a schema that looks fine.

## The three-step change, one step at a time

`ship_to` was one free-text column. It is now `ship_to_street` + `ship_to_city`. Three migrations, and
`MigrationStepsTest` drives them **with data already in the table**, stopping at each version a deploy
would have reached. Applying all four to an empty database — what a normal startup does — proves the
final shape is reachable and nothing at all about whether the path to it was safe.

| Step | Migration | What must be true |
| --- | --- | --- |
| **Expand** | V2: add nullable columns, backfill | the old code, still running, keeps inserting successfully |
| **Deploy** | *not a migration* | the release that stops writing `ship_to` and starts writing the split columns |
| **Contract** | V3: fill nulls, add NOT NULL, drop `ship_to` | nothing writes the old shape any more |

The middle step is the one that is not in `db/migration`, and it is the one teams skip: the two
migrations look like a pair and get deployed together, which is exactly the outage the pattern exists to
avoid.

**V3 is where the waiting lives.** Once `ship_to` is dropped, rolling the *application* back to the
version that read it is no longer possible — the data is gone. So the sequence is not "expand, contract,
done" but "expand, deploy, **wait until you are sure you will not roll back**, contract", and the waiting
is measured in days.

### Two details the tests exist to pin down

**The backfill in V2 must tolerate its own data.** The split is naive on purpose (everything before the
first comma is the street) because a real free-text column contains rows nobody anticipated. Those land
on an explicit `UNKNOWN` — a decision recorded in the schema rather than a silence, so somebody can find
them later and fix them by hand, which is what they will have to do.

**V3 fills the nulls before it constrains.** The rows written by the old code *during the expand window*
have no city. A migration that added NOT NULL without filling first fails on exactly those rows — a
failure that only happens in the environment that had real traffic during the deploy.

## Which backfills are SQL and which are commands

The criterion, and it is short enough to remember:

> **Restating bytes that are already in the row is SQL. Deciding anything, or having to tell anyone, is a
> command.**

This sample has one of each.

| | V2's address split | V4's `handling` |
| --- | --- | --- |
| what it does | splits a string already in the row | applies a rule |
| needs domain knowledge | no | yes — a quantity threshold and a list of remote cities |
| anyone to inform | no (nothing observable changed) | yes — years-old rows now mean something new |
| written as | SQL, inside the migration | a command, through the bus |

V4 therefore adds a nullable column and **stops**. What going through the command channel buys, each
measured in `BackfillChannelTest`:

- **One copy of the rule.** A backfilled legacy row and a freshly placed order with the same inputs get
  the same handling. In SQL the migration would carry a `CASE WHEN` containing its own copy — including
  the city list — drifting from the day the carrier adds an island.
- **An announcement, in the same transaction as the change.** An `UPDATE` has nobody to tell: no event,
  no version bump, no way for a consumer to notice its copy is now wrong. Here the outbox rows and the
  column commit together, so an interrupted backfill has neither decided without announcing nor announced
  without deciding.
- **Idempotence, in the aggregate.** `decideHandling()` returns false when it has nothing to do, so
  running the backfill twice decides nothing and publishes nothing. Not a nicety: a backfill over a large
  table gets restarted, and a step that is not safe to repeat turns a restart into a data question.
- **Pages.** One call, one transaction, one number; the caller loops until zero. A backfill that loads
  the whole table works until the table is large enough to matter, which is exactly when someone runs it.

The aggregate's invariants come free with it: a backfill cannot write a state the domain would refuse.

### `NULL` means "not decided", and it must not be defaulted

`DEFAULT 'STANDARD'` in V4 would be one line, would hold a lock over the whole table, and would assert
something false about every legacy order that should have been expedited. It would also take away the
backfill's ability to find its own work. The read endpoint returns `null` while a row is undecided rather
than guessing — a state the API tolerates for exactly as long as the backfill takes.

**Filling a new column with a plausible default is the most common way to lose data that was never
there.**

## A migration is not a contract change

`OrderPlaced` is version 1 and stayed version 1 across all four migrations. Its `shipTo` is still one
string, even though the table now holds two columns.

That is not laziness. A published contract is not a projection of a table, so a structural migration is
not a breaking change and a consumer reading this before V2 is unaffected by V2 and V3. Had the event
been generated from the row — the "just serialise the entity" shortcut — the split would have silently
become a breaking change published to everyone, with no version bump and no upcaster, and the first
symptom would have been in someone else's service.

The reverse holds too, which is why S21 exists separately: a contract change is not a migration. The two
schedules are independent, and coupling them is what makes people believe a deploy has to be
simultaneous.

## Why `clean` is already off

`clean` drops every object in the schema. The reason it must be off by default is not that anyone would
type it deliberately in production — it is that at 3am, "the migration is stuck, let me just reset it" is
a thought people have, and an available command will eventually be reached for.

Worth a test rather than a sentence because this application has three Flyway configurations and only
one of them is configured by `spring.flyway.*`. Both Boot's instance (`clean-disabled: true`) and an
instance built in code (Flyway's own default since 9.x, which is why neither `MigrationConfiguration` nor
the library's migrator sets it) refuse. The second assertion is the one that would notice a Flyway
upgrade changing its mind.

## Two contexts, one datasource, no shared schema

Sharing a database is a deployment fact; sharing a schema would be a modelling mistake. So:

- **No cross-context foreign key.** `s23_invoice.order_id` references nothing. A cross-context FK is a
  deployment-time coupling: it makes the two tables un-splittable, makes billing's migration depend on
  ordering's having run, and lets a delete in one context be refused by a rule the other owns.
- **No import in either direction.** Billing keeps ordering's id as a plain `String`; importing `OrderId`
  would make billing depend on ordering's domain. Enforced by the library's opt-in cross-context ArchUnit
  rule, which is exactly the rule a shared datasource makes worth turning on — the compiler would not
  mind, and the database would mind later, when somebody tries to split them.

## Five negative controls, each run on its own

| Change | Red | What it measured |
| --- | --- | --- |
| V2 adds the columns `NOT NULL DEFAULT 'UNKNOWN'` | 3 | **More interesting than expected.** The old code's insert does not fail — it succeeds and gets `UNKNOWN`, so the address is *fabricated* rather than rejected. The backfill also stops working (`WHERE ship_to_street IS NULL` now matches nothing). A `NOT NULL DEFAULT` in an expand step is quiet data loss, not a loud failure. |
| V3 drops the pre-fill before `SET NOT NULL` | 2 | the migration fails with SQL state 23502 on the rows the expand window produced — the exact failure that only appears in an environment with traffic |
| V4 adds `DEFAULT 'STANDARD'` | exactly 1 | the migration test catches it; the backfill tests do not, because they construct their own undecided rows. The split is deliberate — one tests the migration, the other the backfill |
| `MigrationConfiguration` skips billing | 4 (across 2 classes) | and three other test classes stay green, which **is** the finding: nothing but a table-level assertion notices that your own context's migration never ran |
| placement gets its own copy of the handling rule | exactly 1 | and the aggregate's unit tests stay green. The only test that can catch a forked rule is one that runs both paths with the same inputs |

## Not demonstrated here

| | |
| --- | --- |
| Migration order across services | Two databases, two deploys, and the rule is the same one S21 states for contracts: the tolerant side goes first. Reasoned, not run — a second service would add nothing to what S4 and S21 already show. |
| Splitting the shared database | The endgame of two contexts in one schema. It needs a second datasource and a data move, and it is a project rather than a sample. |
| Online index creation | `CREATE INDEX CONCURRENTLY` cannot run in Flyway's transaction and needs `-- mvn:no-transaction`-style handling. A real concern at scale, and a Flyway configuration question rather than a DDD one. |
| Blue/green and rolling-deploy mechanics | The expand window's *length* is a deployment property. This sample shows what must be true during it. |
| Backfill throughput | Pages, no rate limit, no progress table. S11 owns scheduled and batch entry points; a multi-hour backfill wants resumability recorded somewhere other than the column it is filling. |
| Adopting the framework on an existing database | `baseline-on-migrate` is on by default for exactly this, and this sample only exercises it incidentally (billing's baseline row). |

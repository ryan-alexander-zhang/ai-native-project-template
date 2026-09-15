# Designs

One structural element per doc. Front matter: `TEMPLATE.md`; body: `TEMPLATE-<kind>.md`.

## Must Include

- `kind`, one of the Kinds below. Body = that kind's template: title, one-line
  `>` summary, every numbered section in order with the template's heading;
  `n/a — <where it lives>` when one does not apply. Nothing added: a section the
  template lacks is another design.
- `## Decisions` — links to the `decision` docs that chose this element's
  tactics; `None.` when empty. Options and costs live there; the `decision`
  carries the edge (`constrains`).
- `## Open Questions` while any is open.

`kind` here is the design taxonomy, not the living / work class of `docs/README.md`.

## Kinds

Five closed dimensions — structure, behaviour, boundary, distribution,
cross-cutting — one kind per grain. Composition is not a kind: the whole is
`ARCHITECTURE.md` §5–§8 and the `domain` model, never a links-only design. No `other`: content fitting no
kind and no Exclude home means a kind is missing; amend here, never widen one.

| Dimension | `kind` | One doc = | Mermaid |
| --- | --- | --- | --- |
| structure · concept | `domain` | one bounded context's model: aggregates, entities, value objects, domain events, relations; no storage | class ×1 |
| structure · storage | `storage` | one aggregate root with its tables, or one read model: columns, types, keys, indexes, DDL | ER ×1 |
| structure · code | `module` | one module: responsibility, public surface, allowed and forbidden dependencies, location | component ×1 |
| boundary · shape | `contract` | one interface this system owns: HTTP resource, event schema, CLI command, file or config format (design tokens included), UI page, library or plugin API | none |
| boundary · translation | `mapping` | one translation between our model and an external one: objects, statuses, event families, error codes | none |
| behaviour · state | `lifecycle` | one state machine (UML): states, transitions, triggers, guards, effects; a saga or process manager is one | state ×1 |
| behaviour · collaboration | `interaction` | one exchange between participants | sequence ×1 |
| behaviour · computation | `algorithm` | one algorithm inside one participant: selection, encoding, signing, parsing, matching | flowchart ×1 |
| cross-cutting | `mechanism` | one mechanism applied by two or more designs: error envelope, auth scheme, idempotency, caching, retry schedule, pagination, correlation, log conventions; values here, any algorithm links an `algorithm`; applied once → it belongs to that design | ≤1 |
| distribution | `deployment` | one runtime topology: nodes, containers, network, volumes, wiring | deployment ×1 |

Titles: "the life of a credential" is an `algorithm`, not a `lifecycle`; a
`*-process` package is a `module`; UI navigation is a `lifecycle` of the
session, a screen is a `contract`.

## Boundary

| Home | Answers | Test |
| --- | --- | --- |
| `rule` `BR` | what the business allows, forbids, derives | remove the software — still true |
| `spec` `FR` / `AC` | what the system observably does when triggered | a GWT can test it |
| `design` | what it is made of, its shape, how it computes | the rest |

Tests apply in that order. A design cites `FR` / `AC` / `BR` ids in backticks;
never restates, amends, or declares one.

- Constants: refund cap, dispute window, fee, promised retention → `BR`, cited.
  Body cap, page bound, token TTL, retry schedule, timeout, key prefix,
  encoding → design, written. A schedule a `rule` commits to is a `BR`.
- `contract` holds the shape the validator reads (type, required, length,
  range) and the codes that exist; which code answers which case is the spec's
  `AC`, "reject when …" its Unwanted `FR`.
- `lifecycle` guards are named predicates in a Guards table: business = a `BR`
  id; technical = one boolean expression over holder columns or trigger
  payload, or an `algorithm` link when no single expression states it. A guard
  needing a sentence is business; mixed = `[a] and [b]`.
- `interaction` links the `mechanism` a failed step falls to; the observable
  result is the spec's Unwanted `FR`.

## Relations

- `informs` — the `spec` / `plan` docs this design feeds; may be empty.
- `implements` — the `quality-<n>-QS-<i>.<k>` this design realises. Every
  tactic (cache, replica, queue, retry, boundary) names one or is a §6 audit
  finding (`QUALITY_SCENARIOS.md`, Omission Heuristics); the linked `decision`
  names what it costs.

## Exclude

| Content | Home |
| --- | --- |
| business rules and constants | `rule/` |
| requirements, acceptance, validation and failure behaviour | the consuming `spec` |
| quality measures (SLO, RPO, RTO, accessibility) | `quality/`, cited via `implements` |
| trade-offs: options, choice, cost | `decision/`, linked from `## Decisions` |
| backup, restore, drills, alerting, cadences, runbooks | `operation/` |
| verification evidence, acceptance checklists | `record/` |
| observed or assumed third-party behaviour, assumption register | `integration/` |
| third-party asset and dataset provenance; licences | `reference/`; `THIRDPARTY.md` |
| discovery artefacts: event storming, domain storytelling, source-reading notes | `analysis/` |
| terms, actor definitions | `CONTEXT.md` |
| commands, Make targets, usage | `DEVELOPMENT.md`, repo `README.md` |
| build gates, lint baselines; coverage floors | `CODE_QUALITY.md`; `TESTING.md` |
| credential-handling policy | `SECURITY.md` |
| test layering; code conventions | `TESTING.md`; `CODE_STYLE.md` |
| shape or notation of another document | that folder's `README.md` / `TEMPLATE.md` |
| system context, constraints, strategy, where to read what | `ARCHITECTURE.md` |
| task breakdown, execution and migration order | `plan/` |
| change history, dated amendments | git; the revision round (`docs/README.md`) |

## Gates

`scripts/check-docs-drift.sh`, each an error:

- `kind` known
- numbered `## <n>.` headings equal the kind's template; `## Decisions` present
- ≤ 150 lines including front matter; Mermaid count per the Kinds table
- no `shall`, no Given / When / Then list or continuation line, no bold
  `FR` / `BR` / `AC` / `QR` / `QS` id

Over a gate → split along the Kinds table, never a new section; a DDL or schema
too long to inline is linked (migration, OpenAPI, JSON Schema file). Splitting an
`active` design: new docs, old one `archived` + `superseded_by`; consumers relink.

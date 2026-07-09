---
id: analysis-00003-aggregate-first-vs-layer-first
type: analysis
role: main
status: draft
parent:
---

# Aggregate-first vs layer-first packaging

Comparative analysis of the two ways to compose the **layer** axis and the
**aggregate** axis inside one bounded context. This is the "outer dimension"
choice deferred by `decision-00005-package-per-aggregate` (which settled *that*
we package per aggregate and hide internals, not *which axis is outermost*). This
is analysis, not a decision — it lays out the trade-offs; a decision doc can pin
the choice.

## The two options (same BC: `ordering` with `Order` + `Customer`)

### A. Aggregate-first → layer inside (a.k.a. package-by-feature / vertical slice)

```
ordering
├── order/
│   ├── domain/          Order (root), OrderLine (pkg-private), Orders (port)
│   ├── application/     PlaceOrderService, ConfirmOrderService
│   ├── infrastructure/  OrderRepository, OrderPo, OrderMapper
│   └── web/             OrderController
├── customer/
│   ├── domain/ …  application/ …  infrastructure/ …
└── shared/              Money, cross-aggregate VOs
```

### B. Layer-first → aggregate inside

```
ordering
├── domain/
│   ├── order/           Order, OrderLine (pkg-private), Orders (port)
│   └── customer/        Customer, Customers (port)
├── application/
│   ├── order/  …        customer/ …
├── infrastructure/
│   ├── order/  …        customer/ …
└── web/ (adapter)
    ├── order/  …        customer/ …
```

Both keep **package-per-aggregate** and package-private domain internals
(decision-00005). They differ only in which axis is the top-level grouping.

## Comparison

| Dimension | A — aggregate-first | B — layer-first |
| --- | --- | --- |
| Cohesion / "one feature, one place" | **Strong** — a feature's whole stack sits under `order/`; add/remove a feature = one folder | Weaker — a feature is scattered across `domain/`, `application/`, … |
| Seeing the layered/hexagonal shape at a glance | Weaker — layers repeat inside every aggregate | **Strong** — the layer boundaries are the top-level structure |
| Enforcing the dependency rule (domain←app←infra) | Harder to see; needs ArchUnit patterns like `..order.domain..` must not depend on `..order.infrastructure..` | **Natural** — maps directly to layer packages, and to **Maven modules per layer** (structure-2) |
| Mapping to build modules | Awkward — a Maven-module-per-aggregate cuts across layers, losing the framework-free-domain compile guarantee | **Clean** — layer = Maven module (structure-2); aggregate = sub-package inside |
| Domain package-private encapsulation | Same (per-leaf-package) | Same |
| Adding a new aggregate | One new top-level folder | Touch each layer package |
| Team ownership by feature | **Easier** — own a folder | Ownership crosses layer packages |
| Risk of cross-aggregate coupling | Slightly higher — sibling aggregate folders are "right there" | Slightly lower — crossing a layer package is more visible |
| Refactor an aggregate into its own module/service later | **Easier** — it is already a self-contained folder | Its code is spread across layer modules and must be gathered |

## What the references do (evidence)

- **ddd-by-examples/library** → **A (aggregate-first)**: the `lending` context is
  split per aggregate (`lending/patron`, `lending/book`, …) and *inside each* live
  `model` / `application` / `infrastructure` / `web`.
  (`docs/reference/ddd-by-examples-library/`)
- **Sairyss/domain-driven-hexagon** → **A**: vertical slice, one folder per use
  case containing its controller/DTO/command/handler.
  (`docs/reference/domain-driven-hexagon/`)
- **Alibaba COLA** → **B (layer-first)**: top-level `adapter` / `app` / `domain` /
  `infrastructure`, with the domain (and other layers) sub-grouped by aggregate.
  (https://github.com/alibaba/COLA)
- **kgrzybek/modular-monolith-with-ddd** → **B within a module**: each module
  (BC) has Domain / Application / Infrastructure as its internal split.
  (`docs/reference/modular-monolith-with-ddd/`)
- **bc-and-layer-samples/structure-2** → **B**: layer = Maven module,
  aggregate = sub-package inside each layer module. Chosen because it makes the
  layer boundary a *compile-time* Maven boundary (see analysis-00004).

So both are mainstream; the split correlates with intent: teaching/feature-team
codebases lean **A**; codebases that want layer boundaries as build-enforced
modules lean **B**.

## Considerations for choosing

- Want **layer boundaries enforced by the build** (Maven module per layer,
  framework-free domain guaranteed at compile time)? → **B** composes cleanly;
  A does not (per-aggregate modules cut across layers). This is why structure-2
  is B.
- Optimizing for **feature teams / frequent add-remove of features / easy future
  extraction of an aggregate into its own service**? → **A** wins on cohesion.
- Single small module, no build-level layer split (structure-1 style)? Either
  works; **A** reads better as the model grows, **B** shows the hexagon better.
- Not in tension with decision-00005: package-per-aggregate and package-private
  internals hold under both; only the outer grouping differs.

## Sources

- `docs/reference/ddd-by-examples-library/`, `docs/reference/domain-driven-hexagon/`,
  `docs/reference/modular-monolith-with-ddd/`
- Alibaba COLA — https://github.com/alibaba/COLA
- Robert C. Martin — Common Closure Principle (package cohesion)
- Related: `analysis-00004-bounded-context-module-structure`,
  `decision-00005-package-per-aggregate`

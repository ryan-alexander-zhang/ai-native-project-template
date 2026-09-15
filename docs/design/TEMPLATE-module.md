# Design: <module> module

> One sentence: the responsibility this module owns and for which context.

## 1. Responsibility

- Owns: <one responsibility, a `CONTEXT.md` term>
- Does not own: <the neighbouring responsibility and the module that has it>

## 2. Components

```mermaid
flowchart LR
  subgraph <module>
    A[<component>] --> B[<component>]
  end
  B --> X[(<store>)]
```

## 3. Public Surface

| Element | Kind | Contract |
| --- | --- | --- |
| <name> | type · function · endpoint · event · command | [<contract design-id>](<contract design-id>.md) or `n/a — internal type` |

## 4. Dependencies

| Depends on | Via | Forbidden | Enforced by |
| --- | --- | --- | --- |
| <module or library> | <its public surface> | <what this module may never import> | <ArchUnit / lint rule path, or `n/a — review`> |

## 5. Location

```text
<path>/    <one line>
```

## Decisions

Links only; the options, the choice, and what it costs live in the `decision`.
`None.` when this element settles no choice.

- [<decision-id>](../decision/<decision-id>.md) — <the tactic it settles>

## Open Questions

Delete this section once every question is closed.

- <what is unknown, and what would close it>

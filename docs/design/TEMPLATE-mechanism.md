# Design: <mechanism>

> One sentence: the cross-cutting concern this mechanism settles and where it applies.

## 1. Concern

- Concern: <errors · authentication · idempotency · caching · retry · pagination · correlation · log and trace conventions>; alerting and cadences are `operation/`
- Applies to: <every `contract` · every `module` · the designs listed in §3>

## 2. Mechanism

| Aspect | Value |
| --- | --- |
| <header name · key format · TTL · algorithm · envelope shape> | <the exact technical value; a business constant cites its `rule-<n>-BR-<i>`> |

```json
{ "<example>": "<one complete instance, when the mechanism has a shape>" }
```

## 3. Applied By

Two rows at least; one user means the mechanism belongs to that design.

| Design | How |
| --- | --- |
| [<design-id>](<design-id>.md) | <where in that design the mechanism is used> |
| [<design-id>](<design-id>.md) | <…> |

## Decisions

Links only; the options, the choice, and what it costs live in the `decision`.
`None.` when this element settles no choice.

- [<decision-id>](../decision/<decision-id>.md) — <the tactic it settles>

## Open Questions

Delete this section once every question is closed.

- <what is unknown, and what would close it>

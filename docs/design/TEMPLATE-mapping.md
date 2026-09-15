# Design: <platform> ↔ <external system> mapping

> One sentence: the two models this translates between and at which boundary.

## 1. Sides

| Side | Model | Source |
| --- | --- | --- |
| platform | [<domain / lifecycle design-id>](<design-id>.md) | — |
| <external system> | <its object model, version> | [<integration doc id>](../integration/<integration-id>.md) |

## 2. Objects

| Platform | External | Direction | Reference kept |
| --- | --- | --- | --- |
| <term> | <external object and id prefix> | both · out · in | <which id is stored, in which `storage` column> |

## 3. Values

| Domain | Platform | External | Notes |
| --- | --- | --- | --- |
| status · event family · error code · currency | <platform value or `lifecycle` state> | <external value(s)> | <one line on the translation only; what the business then does is a `rule`> |

## 4. Unmapped

| Element | Side | Where it lives |
| --- | --- | --- |
| <element with no counterpart> | platform · external | <the design, `integration/` note, or `decision` that handles it> |

## Decisions

Links only; the options, the choice, and what it costs live in the `decision`.
`None.` when this element settles no choice.

- [<decision-id>](../decision/<decision-id>.md) — <the tactic it settles>

## Open Questions

Delete this section once every question is closed.

- <what is unknown, and what would close it>

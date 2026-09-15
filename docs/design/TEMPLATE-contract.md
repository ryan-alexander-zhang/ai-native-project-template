# Design: <interface> contract

> One sentence: the interface this contract fixes, between whom.

## 1. Interface

| Kind | Producer | Consumer | Versioning |
| --- | --- | --- | --- |
| HTTP · event · message · CLI · file · configuration · UI page · library or plugin API | [<module design-id>](<module design-id>.md) | <module design or external actor> | <path prefix, header, schema field, or `n/a — single version`> |

## 2. Operations

| Operation | Request | Response | Outcome codes |
| --- | --- | --- | --- |
| <method + path · event name · command · page> | <schema in §3, or `—`> | <schema in §3> | <every code or outcome name that exists; which one answers which case is the consuming spec's `AC`> |

## 3. Schemas

### <schema>

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| <name> | <type and limits the validator reads> | yes · no | <term>; a business constant cites its `rule-<n>-BR-<i>` |

```json
{ "<example>": "<one complete instance>" }
```

Or, when inlining would break the 150-line gate: `<path to the OpenAPI / JSON Schema file>`.

## 4. Conventions Applied

| Concern | Mechanism |
| --- | --- |
| <errors · authentication · pagination · idempotency · correlation> | [<mechanism design-id>](<mechanism design-id>.md) |

## Decisions

Links only; the options, the choice, and what it costs live in the `decision`.
`None.` when this element settles no choice.

- [<decision-id>](../decision/<decision-id>.md) — <the tactic it settles>

## Open Questions

Delete this section once every question is closed.

- <what is unknown, and what would close it>

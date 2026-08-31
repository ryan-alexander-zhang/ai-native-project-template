---
id: design-00001-example-slug
type: design
status: draft|active|archived
informs: [<spec-id | plan-id>, ...]           # may be empty while the design waits to be picked up (BR-26: a design can precede any spec)
---

# Design: <subject>

> One sentence: the structure this design settles and for what.

## 1. <Structure the body around the subject>

No fixed structure — domain, database, API, integration, process, deployment.
Prefer Mermaid per the folder README's guideline:

- Domain — class diagram · Lifecycle — state diagram · Database — ER diagram
  plus the SQL schema · Interaction — sequence diagram · Branching process —
  flowchart · API — the contract itself, not a diagram.

```mermaid
flowchart LR
  A[<component>] --> B[<component>]
```

## 2. Trade-offs

- <the options considered, the one taken, and what it costs>

## Open Questions

Delete this section once every question is closed.

- <what is unknown, and what would close it>

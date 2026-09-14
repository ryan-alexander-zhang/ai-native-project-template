# Designs

Design docs. Front matter: `TEMPLATE.md`.

## Must Include

- the design for the `spec` / `plan` docs that link it — any size, reusable or
  one-off; never inlined in a `spec` or `plan`
- open questions — what is still undecided

Add more when useful.

## Relations

- `informs` — the `spec` / `plan` docs this design feeds; may be empty while
  the design waits to be picked up.
- `implements` — the `quality-<n>-QS-<i>.<k>` scenarios this design realises. A
  cache, replica, queue, retry, or module boundary exists to make some Measure
  hold; a tactic naming no scenario is an `AGENTS.md` §6 audit finding
  (`QUALITY.md`, Omission Heuristics).

## Exclude

- business rules (`rule/`)
- system requirements and acceptance (the consuming `spec`)
- quality requirements and measures (`quality/`); a design cites the scenarios
  it realises and, in §2, the ones it trades
- task breakdown, execution steps

## Guideline

Prefer Mermaid:

1. Domain — class diagram.
2. Lifecycle — state diagram.
3. Database — ER diagram plus the SQL schema.
4. Interaction — sequence diagram.
5. Branching process — flowchart.
6. API — the contract itself, not a diagram.

## Note

No fixed structure; the body follows the subject.

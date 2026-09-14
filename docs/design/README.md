# Designs

This directory stores design docs.
Use `TEMPLATE.md` for front matter.

## Must Include

- the design for the `spec` / `plan` docs that link it — any size, reusable or
  one-off. Design is never inlined in a `spec` or a `plan`.
- open questions — what is still undecided

Add more when useful.

## Relations

- `informs` — the `spec` / `plan` docs this design is input for. May be empty while
  the design waits to be picked up.
- `implements` — the `quality-<n>-QS-<i>.<k>` scenarios this design realises: a
  cache, a replica, a queue, a retry, a module boundary exists to make some
  scenario's Measure hold, and this field says which. A design that adds such a
  tactic and names no scenario is a finding in the `AGENTS.md` §6 audit
  (`QUALITY.md`, Omission Heuristics).

## Exclude

- business rules (use `rule/`)
- system requirements and their acceptance (use the consuming `spec`)
- quality requirements and their measures (use `quality/`); a design cites the
  scenarios it realises and, in §2, the ones it trades against each other
- task breakdown
- execution steps

## Guideline

Prefer Mermaid:

1. Domain — class diagram.
2. Lifecycle — state diagram.
3. Database — ER diagram plus the SQL schema.
4. Interaction — sequence diagram.
5. Branching process — flowchart.
6. API — the contract itself, not a diagram.

## Note

No fixed structure. Domain, database, API, integration, process, deployment —
structure the body around the subject.

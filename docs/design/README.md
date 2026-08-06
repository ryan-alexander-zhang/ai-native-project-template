# Designs

This directory stores reusable design docs.
Use `TEMPLATE.md` for front matter.

## Must Include

- reusable design content that is worth linking from one or more `spec` or
  `plan` docs

Add more when useful.

## Relations

- `informs` — the `spec` / `plan` docs this design is input for. May be empty while
  the design waits to be picked up.

## Exclude

- business rules (use `rule/`)
- system requirements and their acceptance (use the consuming `spec`)
- task breakdown
- execution steps
- one-off implementation notes that belong inside a single `spec` or `plan`

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

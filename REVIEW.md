# Review Checklist

Use this file to record the project-specific review checklist for this repo.
It starts empty on purpose — fill it in as the project's real risk areas
become clear.

Keep review comments and docs in the canonical terms from
[CONTEXT.md](CONTEXT.md) once that file exists; `scripts/term-check` enforces its
`_Avoid_` lists on prose and code comments (`decision-00005-term-check`).

## Spec impact

The PR's job summary carries the `IMPACT` list from `scripts/trace-check --impact`
(`decision-00004-doc-impact-review`): for each changed file, the ACs whose tests
live in or import it, and the designs whose `anchor:` covers it. For every AC
listed, read its Given / When / Then against the new code and record one of:

- **holds** — the behaviour the AC describes is unchanged; say why in one line.
- **broken** — the code no longer does what the AC says. Blocks merge until the
  code is fixed or the AC is revised through a revision round.
- **revise** — the code is right and the AC is now wrong or incomplete. Blocks
  merge until the revision round has run.

When the list is `IMPACT none`, write one line confirming that the diff sits
outside every acceptance criterion; that is a finding, not a pass.

## Checklist

- [ ] <add the correctness, safety, and consistency checks this project cares about>

Add checks as risk areas surface, for example: data integrity, money and
accounting, auth and tenancy isolation, error handling, concurrency, and
idempotency. Each check should say what to reject, not just what to look at.

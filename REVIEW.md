# Review Checklist

Use this file to record the project-specific review checklist for this repo.
It starts empty on purpose — fill it in as the project's real risk areas
become clear.

Keep review comments and docs in the canonical terms from
[CONTEXT.md](CONTEXT.md) once that file exists; `scripts/term-check` enforces its
`_Avoid_` lists on prose and code comments (`decision-90005-term-check`).

## Spec impact

The PR's job summary carries the `IMPACT` list from `scripts/trace-check --impact`
(`decision-90004-doc-impact-review`): for each changed file, the ACs whose tests
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

- [ ] Reject a dependency on `org.springframework` or `com.baomidou` in any module without a technology suffix (`ARCHITECTURE.md` §5.2 invariant); reject a new module that does not fit one of the four shapes.
- [ ] Reject a tenant-relative key that omits the tenant, and any dedup key that puts the tenant into a globally-unique id (`CONTEXT.md`, Tenant-relative Key).
- [ ] Reject code that reads the actor or the tenant from the command payload, or that writes to `TenantContext` from business code (`CONTEXT.md`, Actor / TenantContext).
- [ ] Reject an Operation Outcome derived from an HTTP status or an exception type, and an Operation Log used as an audit log without the append-only guarantees (`CONTEXT.md`, Operation Log language).
- [ ] Reject a Deadline that lives only in process memory, and an Effect that is not idempotent on redelivery (`CONTEXT.md`, Deadline / Effect).
- [ ] Reject a change to a public contract module without the matching update to `CHOOSING-MODULES.md` / `CONFIGURATION.md` under `aipersimmon-ddd/`.

Add checks as risk areas surface, for example: data integrity, money and
accounting, auth and tenancy isolation, error handling, concurrency, and
idempotency. Each check should say what to reject, not just what to look at.

---
id: decision-00006-drop-the-role-field
type: decision
status: active
parent:
---

# Drop `role`; amend documents in place

## Context

Front matter carried `role: main|patch`. `main` marked the canonical document for
a topic; `patch` marked a scoped addendum to an `active` main document, with
`parent` pointing at the document it extended.

Across ~251 instance documents, 247 were `main` and 4 were `patch`. Reading the
four shows the ratio is worse than it looks:

| Doc | `parent` | What it actually was |
| --- | --- | --- |
| `decision-00016-durable-runtime-staged-message-identity` | `decision-00013` | a genuine addendum: keeps the original claims, relaxes one clause about who mints a message id |
| `issue-00009-version-evolution-semantics` | `decision-00014` | not an addendum — an issue reporting that the ADR's text contradicts the code |
| `issue-00010-verify-kafka-dlt-with-embedded-broker` | `issue-00003` | not an addendum — an issue reporting that an earlier acceptance claim was over-stated |
| `issue-00100-a-scheduled-purge-steals-the-lock-from-its-own-test` | `report-00003` | not an addendum — a test defect found during a review |

Three of the four used `role: patch` to say "I am a follow-up to that document",
which is a *relation*, not a document kind. decision-00004 gave relations their
own fields, and `blocks` expresses all three correctly. So the field's real usage
is one document in 251.

That leaves `role` as a document-kind marker doing a relation's job — the same
category error decision-00004 removed from `parent`. It also works against
`DOCUMENT.md`'s own rule to keep one current document per topic: a patch doc is a
parallel structure a reader has to assemble by hand, and with no per-folder index
there is no reliable way to discover that a document has addenda at all.

## Decision

Remove `role` from front matter and from every template. Amend documents in
place; git holds the history.

For a document that genuinely must not be rewritten — published, or cited from
outside this repo:

- **fully replaced** → write the new document with `supersedes: [<old id>]` and
  set the old one to `archived`.
- **one clause narrowed** → write a new document stating what it narrows, and add
  a one-line pointer in the old one. Adding a pointer is not a rewrite.

`parent` gets simpler as a side effect: with the `patch` → main case gone it means
containment or stage advance only.

## Options considered

- **Keep `role`.** Rejected: one document in 251 justifies neither a field on
  every document nor the rules explaining when to reach for it, and the field
  invites the misuse seen in three of its four uses.
- **Replace it with an `amends` relation field.** Rejected: it would express the
  one legitimate case correctly, but adding a field to serve a single document
  puts back the complexity decision-00004 removed. `supersedes` plus a pointer
  covers it.
- **Keep `role` but forbid `patch` for `issue`.** Rejected: it fixes the three
  misuses and leaves the field earning its keep on exactly one document.

## Consequences

- `docs/README.md` — `role` is out of the front matter block; the
  "When to use `role: patch`" section became "Amending a document"; the `patch`
  row is gone from the `parent` table; "keep one main version for one topic"
  became "keep one document per topic, and amend it in place".
- All 16 `docs/*/TEMPLATE.md` — the `role` line is removed.
- `docs/decision/README.md`, `docs/issue/README.md`, `docs/spec/README.md` —
  wording that leaned on main/patch is gone.
- `DOCUMENT.md` — placement and the Definition of Done no longer say "main doc".
- The six instance docs on `main` dropped their `role: main` line.
- Trade-off accepted: an amendment's history now lives in git rather than in a
  separate document. For a repo whose docs are all internal that is the cheaper
  side of the trade; a repo with externally signed-off documents should revisit it.
- Migration debt on `lang/java/ddd`: strip `role:` from ~251 docs (mechanical),
  and convert the four `patch` docs — `issue-00009`, `issue-00010`, `issue-00100`
  take `blocks: [<their current parent>]`; `decision-00016` stays a standalone
  decision that narrows `decision-00013`, which gets a one-line pointer.

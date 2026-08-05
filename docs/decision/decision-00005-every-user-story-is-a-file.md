---
id: decision-00005-every-user-story-is-a-file
type: decision
status: active
parent:
---

# Every user story is its own file; a spec never inlines one

## Context

`us/` docs own the requirement unit: the value statement, the EARS requirements
numbered `us-<n>-FR-<i>`, and the GWT acceptance numbered `us-<n>-AC-<i>.<k>`.
The requirement id deliberately carries the story's document number, which is
what makes `us-00001-FR-2` globally unique and citable from a plan, a test, or an
acceptance record.

The taxonomy also allowed a small-spec exception: one small story could be
written inline in the spec instead of getting its own file, to avoid producing
thin files.

Those two rules contradict each other. An inline story has no `us` number, so its
functional requirements have no id namespace — the scheme simply does not define
one for them.

The failure is not hypothetical. A spec written under the exception inlined four
stories, then declared that the capability had no story-level FR and relabelled
every requirement as cross-cutting `spec-<n>-XFR-<i>`. The requirements survived,
but they moved out of the story layer, so the four stories were left as prose with
no acceptance criteria and nothing for a `record` to verify against. The exception
does not just permit thin files; it pushes requirements into the wrong namespace
and out of story-level acceptance.

## Decision

Remove the inline-story exception. Every user story is its own `us/` doc, however
small — one story is enough to justify a file.

- A spec lists and links its stories in a table and carries no story text itself.
- `us-<n>-FR-<i>` stays the only namespace a functional requirement lives in.
- `spec-<n>-XFR-<i>` is reserved for requirements that genuinely belong to no
  single story.
- The story table no longer repeats each story's `status`; the `us` doc owns it.

This removes the story half of the pattern that decision-00002 cited as its
precedent. The design half is unchanged: a spec may still inline a small
technical design, because a design carries no numbered requirement ids and so has
no namespace to lose.

## Options considered

- **Keep the exception and define ids for an inline story**, e.g.
  `spec-<n>-US<k>-FR-<i>`. Rejected: a second requirement namespace to teach,
  cite, and check, so that a file need not be created — and every reference to a
  requirement would first have to establish which namespace it is in.
- **Keep the exception and cap it at one story, enforced.** Rejected: the cap was
  already written and was exceeded anyway. A rule whose only guard is prose is not
  a rule, and the id gap remains for the one story it does allow.
- **Allow inline stories but forbid requirement ids in them.** Rejected: a story
  without requirements is not a requirement unit, so this keeps the file count
  down by giving up the thing `us/` exists for.

## Consequences

- `docs/spec/README.md` — the small-spec story exception is deleted from the User
  Stories section. Nothing else in that section changed: "Each user story is its
  own `us/` doc" was already the stated default and is now simply absolute.
- `docs/spec/TEMPLATE.md` — the inline-story blockquote is deleted, and the story
  table drops its `Status` column so a story's status lives only in its own doc.
- `docs/us/README.md` — the closing note no longer suggests keeping small stories
  inline.
- `docs/decision/decision-00002-spec-links-a-design-doc.md` — its Context cited
  the story exception as precedent; annotated to point here.
- Trade-off accepted: small features produce more, thinner files. This buys one
  id namespace instead of two and keeps every functional requirement inside
  story-level acceptance.
- Migration debt: `spec-00002-multi-tenancy` on `lang/java/ddd` has four inline
  stories. Splitting them means authoring the FR/AC they never had, which is
  domain work, not a mechanical move; it is outstanding on that branch.

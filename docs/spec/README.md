# Specs

This directory stores feature specs. A spec fuses the feature view and the
technical design into one document.
Use `TEMPLATE.md` for front matter.

## Scope

A spec is **one feature** — a coherent, shippable capability a small team
delivers as one increment (days to ~2 weeks). It orchestrates one or more user
stories, the cross-cutting requirements, and the technical design. A single
sprint-sized story is not its own spec; it is a story under one.

## Must Include

- context aligned to `CONTEXT.md`
- the user stories the feature delivers (see below)
- cross-cutting / system requirements that belong to no single story (EARS numbered `spec-<n>-XFR-<i>`, acceptance `spec-<n>-XAC-<i>.<k>`)
- technical design — by default a link to a `design/` doc; inline only as a small-spec exception (see below)
- error handling mapped back to requirement ids

## User Stories

- Each user story is its own [`us/`](../us/README.md) doc that owns its value
  statement, EARS requirements, and GWT acceptance. The spec lists and links
  them in a table.
- Reference a requirement globally as `us-<n>-FR-<i>`.
- Small-spec exception: one small story may be written inline in the spec
  instead of a separate file. Split into files once there are multiple stories,
  or a story is reused / tracked on its own.

## Technical Design

- By default the technical design lives in its own [`design/`](../design/README.md)
  doc, and the spec links it. This keeps every design collectable in one folder
  rather than scattered across specs, lets a design be written first and
  associated with a spec later, and lets one design be reused by more than one
  spec.
- Small-spec exception: keep the design inline in the spec's Technical Design
  section when the scope is small. Extract it to a `design/` doc once it is
  reused or needs independent review.

## Parent

`parent` may be a `prd`, an `idea`, or empty when the spec is itself the entry
point. Use the upstream main doc id when one exists.

## Exclude

- long product background (belongs in `prd/`)
- task breakdown (belongs in `plan/` or `task/`)
- process reports (belongs in `record/`)

## Note

A spec answers what the system should do and how it is shaped. Keep requirements
and acceptance in the linked user stories; keep this doc the feature-level map.

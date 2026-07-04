---
id: decision-00002-spec-links-a-design-doc
type: decision
role: main
status: active
parent:
---

# A spec links its technical design to a design doc by default

## Context

The original template treated a spec's Technical Design section as inline by
default, extracting a `docs/design/` doc only when the design was reused across
specs or large enough to review on its own. In practice this scatters design
content across many spec files: there is no single place to browse all designs,
a design cannot easily be authored before its spec, and reuse of one design by
several specs is awkward.

The competing concern is friction. A spec is meant to fuse the feature view and
the technical design into one readable document, and forcing every trivial
design into its own file adds ceremony and produces many thin files.

The repo already externalizes user stories the same way: a `us/` doc owns each
story by default, with a small-spec exception that allows one story inline. The
machinery for an independent design already exists — an extracted design's
`parent` may be a `spec`, a `plan`, or empty.

## Decision

Make a linked `docs/design/` doc the default for a spec's technical design,
mirroring how `us/` docs are handled:

- By default the technical design lives in its own `design/` doc and the spec
  links it. A design may be written first and associated with a spec later, and
  one design may be linked by more than one spec.
- Small-spec exception: keep the design inline in the spec's Technical Design
  section when the scope is small; extract it to a `design/` doc once it is
  reused or needs independent review.

This is a change of default, not a new capability: independent, reusable design
docs were already supported.

## Consequences

- `docs/spec/TEMPLATE.md` — Section 4 (Technical Design) and the Links block
  state the link-by-default rule with the small-spec inline exception.
- `docs/spec/README.md` — the "Must Include" line and a new "Technical Design"
  section describe the default and the exception.
- `docs/design/README.md` — "When To Extract" leads with the design-doc default
  and keeps the small-spec inline exception.
- Benefits: all designs are collectable in one folder, design-first authoring is
  natural, and one design can be reused across specs.
- Trade-off accepted: small specs may still inline their design to avoid
  producing thin files, so the design folder is not guaranteed to hold every
  design.

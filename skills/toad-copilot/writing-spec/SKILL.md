---
name: writing-spec
description: >
  Save an engineering spec in `docs/spec` as the current project's standard spec.
  Use this whenever the user wants a spec, technical spec, engineering design, behavior spec, or
  wants to turn approved requirements into a durable engineering spec in `docs/spec`, even if
  they do not explicitly say "spec". Use it both when the user needs help exploring 2-3
  implementation-shape approaches before choosing and when they want the final spec written with
  repo-standard front matter.
compatibility: >
  Requires python3, a POSIX environment, and write access to the target project's `docs/spec`.
---

# Writing Spec

Use this skill when the user wants an engineering spec saved in the current project's
`docs/spec`.

## What Counts As Spec Work

Use a spec when the document should define:

- feature boundaries
- behavior constraints
- inputs and outputs
- states and rules
- non-functional requirements
- verification expectations

Do not use a spec for:

- long product background that belongs in a PRD
- task breakdown that belongs in a plan
- architecture decisions that belong in an ADR
- reports, status updates, or process notes

## Defaults

- Use `role: main` unless the user explicitly asks for a patch.
- Use `status: draft` unless the user explicitly asks for `active` or `archived`.
- A `main` spec must use a concrete `prd-*` parent.
- A `patch` spec must use a concrete `spec-*` parent.
- Specs live in `docs/spec`.
- Do not use a bundled Markdown body template. Generate the file shell with `create_spec.py`, then
  write the body directly.
- Do not impose a fixed section template on every spec. The structure should match the approved
  design and the needs of the topic.
- Keep the spec short, concrete, and behavior-focused.

## New Main Spec Interaction Model

For a new main spec, do not jump straight to drafting. Align with the spec-writing portion of the
repo's brainstorming workflow:

- inspect the parent PRD and nearby durable docs or repo context that constrain behavior
- assess scope early; if the request actually contains multiple independent subsystems, say so and
  help the user decompose it before spec drafting
- offer the visual companion as its own message when upcoming questions are genuinely visual
- ask one short clarification at a time only when a material behavior constraint is missing
- present 2-3 approaches with trade-offs and a recommendation
- treat those approaches as design-direction options, not as the spec outline or the whole spec
  scope
- after the user picks a direction, present the design in sections scaled to the complexity of the
  topic
- ask for approval after each design section
- only create the spec file after the design as a whole is approved

The goal is not to brainstorm product scope from scratch. The goal is to turn known requirements
into a clear engineering spec with one explicit design direction.

## Patch Spec Interaction Model

For a patch spec:

- inspect the parent spec and the new change request first
- if the patch changes the design materially, you may use the same `A/B/C` pattern
- otherwise ask one short clarification only when the scope is unclear, then draft directly

## Workflow

1. Infer whether the request is to create a new main spec, create a patch spec, or review an
   existing spec.
2. If the user wants a review of an existing spec, read the document and report the gaps first:
   missing behavior rules, weak verification, unclear states, ambiguity, or scope drift. Do not
   rewrite unless the user asks.
3. For a new main spec, require a concrete `prd-*` parent before drafting. If the parent is
   missing, ask for it or help identify it first.
4. For a patch spec, require a concrete `spec-*` parent before drafting.
5. Read the source material that defines the request:
   - the user's request
   - the conversation constraints
   - the parent PRD for `main`
   - the parent spec for `patch`
   - nearby docs or code only when needed to make the behavior boundaries credible
6. Before asking detailed questions for a new main spec, assess whether the request is too large
   for a single spec. If it spans multiple independent subsystems, help the user decompose it and
   focus this run on the first spec-sized slice.
7. If upcoming discussion is genuinely visual, offer the visual companion exactly as
   `brainstorming` requires: its own message, no combined content, and only once.
8. Ask one short clarification at a time only if a material behavior constraint is still missing.
9. For a new main spec, present 2-3 design-direction approaches with trade-offs, recommend one,
   and wait for the user's choice before moving into the actual spec design.
10. After the user chooses a direction for a new main spec, present the design in sections scaled
    to the topic. Use the sectioning that best fits the design. Cover the important system
    boundaries, behaviors, flows, failure cases, and verification story, but do not force one
    fixed section pattern.
11. Ask after each design section whether it looks right so far. Revise when needed.
12. Only after the design as a whole is approved, or once a patch spec is clear enough to draft,
    run:
    `python3 scripts/create_spec.py "<slug or title>" --json [--role main|patch] [--status draft|active|archived] --parent <id>`
    Resolve `scripts/create_spec.py` relative to this skill directory.
13. Open the new file path returned by the script.
14. Keep the generated front matter and title line. Write the body directly in the structure that
    best fits the approved design. Use headings when they help, but do not force a fixed section
    schema. Make sure the written spec still covers the behavior and constraints that matter for
    the topic.
15. Run the validation loop below until it passes.
16. Run the self-review below and fix any issues inline.
17. If the self-review changed the file, run `validate_doc.py` again before reporting success.
18. Report the path back to the user and ask them to review the written spec before moving on to
    implementation planning. The next skill after approval is `writing-plans`.

## Validation Loop

1. Save the edited spec.
2. Run:
   `python3 scripts/validate_doc.py "<file path>"`
   Resolve `scripts/validate_doc.py` relative to this skill directory.
3. If validation fails:
   - read the error message
   - fix the spec in place
   - save the file
   - run the validator again
4. Only move to self-review after the validator passes.

## Self-Review

After the spec passes `validate_doc.py`, read it again with fresh eyes and check it against the
user request and source material. This is a checklist you run yourself, not a separate reviewer.

**1. Placeholder scan:** Any `<...>`, `TBD`, `TODO`, or other unresolved placeholder content? Fix
it.

**2. Internal consistency:** Do the scope boundaries, behaviors, states, rules, and verification
story all describe the same solution without contradiction? Fix any mismatch.

**3. Scope check:** Is the spec still an engineering behavior document, or did it drift into PRD
background or plan-level task breakdown? Tighten it if needed.

**4. Ambiguity check:** Could a key behavior, input, state, or rule be interpreted two different
ways? Pick one and make it explicit.

**5. Verification check:** Does the document explain how the important behaviors, failure modes, or
constraints can be verified? Fix any weak coverage.

If you find issues, fix them inline and rerun `validate_doc.py` before reporting success.

## Gotchas

- Do not invent the file name or front matter by hand. Always start with `scripts/create_spec.py`.
- `parent` must match `role`: `main` uses a `prd-*` id, while `patch` uses a `spec-*` id.
- Do not treat the 2-3 approaches step as the whole spec. It is only one decision step before the
  actual design presentation.
- Do not create the file until the user has chosen a direction and approved the design sections.
- Specs are not PRDs and not plans. Keep product narrative and task breakdown out of the document.
- There is no bundled body template and no fixed section contract. Let the approved design drive
  the document shape.
- Do not move straight into implementation. After the user reviews the written spec, the next skill
  is `writing-plans`.

## Generated File

By default the script saves to `docs/spec` in the current project. Use `--output-dir` only when
the user explicitly wants a different location.

- front matter with `id`, `type`, `role`, `status`, and `parent`
- a title line
- a body shaped to the approved design and the topic at hand

Fill the generated file in place.

---
id: prd-00001-example-slug
type: prd
status: draft|active|archived
parent: <idea-id | empty when the prd is the entry point>
---

# <Product name> PRD

## Summary

<One sentence: what the product is and who uses it. No implementation.>

## Vision and Goals

<Why this exists and what changes when it does. Then the goals, one bullet
each, each one observable.>

- <goal>

## Actors

- **<actor>**: <what this actor does with the product>
- **<actor>**: <…>

## Scale and Context

<The facts the `quality` doc's profile is derived from (`QUALITY.md`, Profile).
Orders of magnitude and classes, not targets; each is a fact about the business
someone can vouch for.>

- **Actors and frequency**: <how many of each actor, how often they act, when>
- **Data**: <what is stored and moved, how much, how fast it grows, how long it is kept>
- **Sensitivity**: <whose data, which regulation if any>
- **Geography**: <where actors and data live>
- **Operations**: <who runs it, when; what an hour of downtime costs>
- **Growth**: <launches, campaigns, the horizon this version must hold for>
- **Budget**: <the cost posture, if one is set>

## Scope

### In scope (MVP)

- <what the first release covers>

### Out of scope

- <what is deliberately excluded, and why when it is not obvious>

## Functional Requirements

<What the product must do, numbered, in the user's language. A PRD owns no
formal requirement ids — `spec/` does.>

1. **<capability>**: <what it must do>
2. **<capability>**: <…>

## Quality Goals

<The quality attributes this product is judged on, ranked top first, in the
user's language and without numbers. Each names its `QUALITY.md` tag. The
`quality` doc that takes this PRD as `parent` turns each into measured
requirements.>

1. **<goal>** (<tag>): <why it ranks here, and what happens when it is missed>
2. **<goal>** (<tag>): <…>

## User Experience

- <the experience bar this product is held to, stated so it can be judged>

## Risks and Dependencies

- **Dependency**: <what must already exist for this to work>
- **<risk>**: <what could go wrong and what it would cost>

## Open Questions

Delete this section once every question is closed.

- <what is undecided, and what would close it — who decides, or what evidence>

# Guidelines 
## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- Reuse before build, in this order: this repo, the framework or starters,
  present dependencies, a maintained open-source library. Build only when none
  fits; record why in a `decision`.
- Every new dependency is a `decision`: what it supplies, what was compared,
  why it won.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Language Consistency

**Use one canonical term for one concept.**

- `CONTEXT.md` is the glossary; create it from `CONTEXT_TEMPLATE.md` if absent.
- A term conflicting with `CONTEXT.md`: stop, resolve, then continue.
- Vague or overloaded language: propose one precise term, test it against concrete scenarios.
- Stated behavior conflicting with code or docs: call out the mismatch.
- Update `CONTEXT.md` as soon as a term is resolved. Glossary only: no implementation, specs, or decisions.

## 6. Document Workflow

- Document work, status transitions included: `DOCUMENT.md`.
- A `spec`, `rule`, `quality`, or `design` draft written or substantively changed is
  audited — before any human review — by a subagent that did not write it: against the
  folder `README.md`, then against the content. Name missing rules, cases, and GWTs,
  readings taken silently, every value it cannot confirm. Each finding becomes an
  amendment or a named Open Question; what only a domain owner can settle is never an
  assumption.
- Acceptance for a `spec` or `rule`: derive per `ACCEPTANCE.md`. Scenarios for a
  `quality` doc: per `QUALITY.md`. A `design` audit includes the sensitivity check —
  each tactic against the scenario list.

## 7. Output Discipline

One sentence when one sentence answers it; expand only on request. Formatting as
short as the content: prose for short answers, no headings or bullets over a single
conclusion. Documents too.

## 8. Development Workflow

- Implementation: `DEVELOPMENT.md`. Dispatch it — an `open` plan task, an `open` issue
  fix, or a small change with no plan — to a subagent that writes only code, tests, and
  the files the task names. Then `TESTING.md`.
- `ARCHITECTURE.md` is the architecture index; create it from `ARCHITECTURE_TEMPLATE.md` if absent.
- Before the first implementation `plan` turns `open`, fill the project-derived root guides
  from their templates and the `active` decisions/designs: `ARCHITECTURE.md`, the Commands in
  `DEVELOPMENT.md`, `PERFORMANCE_TESTING.md` / `RESILIENCE_TESTING.md` when any `active`
  quality scenario is `load` / `chaos`, the project-specific values in `TESTING.md` /
  `CODE_STYLE.md` / `CODE_QUALITY.md`. Never implement against a root guide still holding
  template placeholders.
- A failing quality gate, or a review flagging complexity or duplication: refactor per
  `CODE_QUALITY.md`; never raise a threshold or suppress a finding.
- A bug found during any task: first a `docs/issue` doc — root cause from first principles,
  reproduced by a failing test (`docs/issue/README.md`) — then the fix. If no `spec` /
  `rule` / `quality` doc covers the behaviour, write or amend it first (revision round);
  the issue `blocks` it.
- Never write code or tests against a `draft` doc; `active` (or `open` for a work item) first.
- Before a `plan` whose `implements` puts `spec`/`rule`/`quality` items in scope becomes
  `resolved`, a subagent verifies from the docs: every linked GWT has a passing test; every
  linked `quality` scenario holds per its method and stage with the evidence `QUALITY.md`
  names; no `spec-<n>-FR-<i>`, `rule-<n>-BR-<i>`, or `quality-<n>-QR-<i>` is unverified;
  every `active` `decision` in reach has passing `enforced_by` tests or its §4 reason. Then
  a `docs/record/` acceptance checklist linking the GWT and QS ids. Any gap blocks `resolved`.
- `skills/audit-architecture` (fresh-context subagent) before a feature-sized `plan` becomes
  `resolved`, and after 20+ PRs since the last audit report. An open finding blocks `resolved`.

## 9. Autopilot Mode

- `/autopilot <prompt>` (or "run `AUTOPILOT.md`"): one intake round, then unattended to a
  PR on an `autopilot/` branch. The human rounds in §1, §5, §6 and `DOCUMENT.md` are
  replaced exactly as `AUTOPILOT.md` states; a choice made in a human's place is a
  `decision` with `decided_by: agent`.

---
id: rule-00001-example-slug
type: rule
status: draft|active|archived
informs: [<spec-id | design-id | plan-id>, ...]   # the docs these rules are input for
---

# Rule: <what these rules govern>

> One sentence: the business question these rules answer.

## 1. Applicability

- Applies to: <the cases these rules govern>
- Does not apply to: <neighbouring cases, each needing its own rule doc>

## 2. Terms

| Term | Definition |
| --- | --- |
| <term the rules are built on> | <how it is read or computed, precisely enough to decide a rule> |

## 3. Rules

- **rule-00001-BR-1** (Definition) <how a value is derived — a derived number
  states rounding direction and precision>
- **rule-00001-BR-2** (Constraint) <what must never be true>. On violation:
  <what the business does>

### rule-00001-BR-3 … BR-5 (Decision) <what this table decides>

Hit policy: `UNIQUE`

| # | <input> | <input> | <outcome> |
| --- | --- | --- | --- |
| **rule-00001-BR-3** | (30, 60] | … | … |
| **rule-00001-BR-4** | … | … | … |
| **rule-00001-BR-5** | *(otherwise)* | | … |

## 4. Acceptance (GWT)

- **rule-00001-AC-1.1** (rule-00001-BR-1)
  Given <precondition>
  When <trigger>
  Then <outcome>

- **rule-00001-AC-2.1** (rule-00001-BR-2)
  Given <a state that violates the constraint is attempted>
  When <trigger>
  Then <the violation response>

## 5. Open Questions

Delete this section once every question is closed.

- <BR id> — <what is unknown, and what would close it>

## Links

- Consumed by: <spec ids — mirror of `informs`, kept for readers>
- Supersedes / superseded by: <rule id, when a policy change replaces this set>

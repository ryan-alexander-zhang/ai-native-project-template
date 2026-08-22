---
id: decision-00001-example-slug
type: decision
status: draft|active|archived
motivated_by: [<id>, ...]                     # what created the need for this choice
constrains: [<id>, ...]                       # docs this choice binds that don't declare `implements` on it
---

# Decision: <the choice, in one line>

> <One or two sentences: what is decided, and what it overturns or replaces.>

## 1. 需要做这个决定的原因

<The situation that forces a choice: what is broken, missing, or ambiguous
today, stated as observable facts rather than preferences. Cite the doc,
requirement id, or issue that surfaced it.>

## 2. 决定

| # | 做法 | 理由 |
| --- | --- | --- |
| 1 | <what is decided, precisely enough to implement> | <why this one> |
| 2 | <…> | <…> |

## 3. 考虑过的其他选项

| 选项 | 结论与理由 |
| --- | --- |
| <the option> | **否决**。<why not — the concrete cost or the premise it gets wrong> |

## 4. 后果

**接受的代价**

- <what this choice costs, and the mitigation if there is one>

**得到的**

- <what it buys>

**不变的**

- <what is explicitly unaffected, so readers do not over-read the decision>

## 5. 这个决定约束什么

- `<doc-id>`：<the sections or requirement ids this decision binds>
- <config file / code location the choice reaches, when it does>
- <the standing constraint on future work: what must not be reintroduced, and
  what a later doc falling under this decision must backfill into `constrains`>

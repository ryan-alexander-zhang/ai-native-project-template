# Code Quality

## Purpose

SOP for keeping code inside this repo's quality gates.

Sections 2 and 3 are **fill-in**: the gates and thresholds depend on the language
and toolchain you bring. Everything else is language-neutral — keep it as written
unless you are deliberately changing the way of working.

When a gate fails, or a review flags complexity or duplication:
**solve it per this file. Never raise a threshold or suppress a finding just to
make a build pass.**

Coverage bars are not defined here — [TESTING.md](TESTING.md) owns them.

## 1. Metrics — what each catches

| metric | counts | flags | lever |
|---|---|---|---|
| Cyclomatic complexity | decision points (`if`/loop/`case`/`&&`/`\|\|`/`?:`) | test & change risk (paths) | fewer branches |
| Cognitive complexity | nesting depth + linear-flow breaks | reading difficulty | flatten + extract |
| NPath complexity | product of independent branches | (multiplicative; noisy) | split function |
| Parameter count | params | often injected collaborators, not data | param object / tune |
| Members per type | methods or functions | often per-item types | split / tune |
| God class | size + reach into other types + low cohesion | several types glued into one | Extract Class |
| Duplication | repeated token runs across files | copy-paste | DRY-extract |

Rule names differ per tool; the metric is what matters.

Metrics are **review triggers, not design goals.** Cognitive complexity is the
primary readability guard; cyclomatic and NPath are secondary.

## 2. Enforced gates — fill in

List every check that fails the build. A check that only warns is not a gate.

| gate | tool | scope | config |
|---|---|---|---|
| Format | *(none yet)* | — | — |
| Complexity + duplication | *(none yet)* | — | — |
| Static analysis | `tsc --noEmit` | `tools/whiteboard/{src,web/src,test,web/test}` | `tools/whiteboard/tsconfig.json` |
| Coverage | `vitest` + `@vitest/coverage-v8` | `tools/whiteboard/{src,web/src}`, less `web/src/main.tsx` and the vendored `web/src/components/ui/**` (see §6 and [decision-00001](docs/decision/decision-00001-whiteboard-ui-stack.md) §4) — bar per [TESTING.md](TESTING.md) | `tools/whiteboard/vitest.config.ts` |

Record where the shared config lives. If more than one build file carries the
same gate configuration, name each one here — they must be changed together.

## 3. Tuned thresholds (and why) — fill in

Start from the tool defaults. Record every deviation here **and** next to the
config itself, with the reason. A tuned value with no recorded rationale is
indistinguishable from a value that was raised to silence a failure.

| check | default | this repo | rationale |
|---|---|---|---|
| `<check>` | `<default>` | `<value>` | `<why the default does not fit a correct pattern here>` |

## 4. Refactoring levers (effect per metric)

| technique | cyclomatic | cognitive |
|---|---|---|
| Guard clause / early return | ~ | ↓↓ |
| Extract function (named step) | ↓ local | ↓ |
| Name a boolean condition | ~ | ↓ |
| Strategy / polymorphism (repeated type-switch) | ↓ caller | ↓ |
| Decision / transition table | ↓↓ | ↓↓ |
| Reduce mutable state (immutable, staged results) | ~ | ↓↓ |
| Extract Class (split a God Class by field-cluster) | ↓ | ↓ |

## 5. SOP — refactoring order (do not skip step 1)

1. **Test first.** Characterize current behavior + key branches with tests that pass on the *unchanged* code — the safety net. (Pure move/extract with full existing coverage may reuse it; state which tests cover the change.)
2. **Flatten nesting** — guard clauses / early return. Domain code raises a named error, never a silent empty return.
3. **Extract by business step** — names express intent (`priceAndValidateLines`, not `handle1`).
4. **Find the repeated decision dimension** (payment method, order state, level, channel).
5. **Pick the structure:** type-varying behavior → Strategy · state machine → transition table / State · finite input combos → decision table · standalone constraint → Specification · fixed sequence → Pipeline · God Class → Extract Class by cohesive field-cluster.
6. **Reduce shared mutable state** — immutable value objects, staged/derived results.
7. **Re-measure + human review.** Metric down ≠ better design.

## 6. Resolve → Tune → Suppress (strict priority)

1. **Solve (refactor).** Default. Extract the genuine outlier; do not design around the number.
2. **Tune a threshold.** Only when a high count is *inherent to a correct pattern* in this stack. Document the reason in §3 and in the config. **Never raise a threshold to swallow one outlier — refactor the outlier.**
3. **Suppress.** Only for framework boilerplate or a true false positive. Must be **visible + reasoned**: an entry in the tool's exclusion file, or an inline suppression carrying a comment. Prefer narrow and local over global.

## 7. Anti-patterns (never)

- Extract that only **relocates** nesting — complexity hidden, not removed.
- Class explosion to kill one small, stable `switch`.
- Boolean params selecting flows: `process(order, true, false)` → name the flows.
- Comments explaining complex code instead of simplifying it.
- Metric-as-goal: the number drops while rules scatter and the debug chain grows.

## 8. New vs legacy

Block **new** violations at the gate. Record legacy as debt and ratchet
thresholds down over time — do not mass-rewrite a working system in one pass.

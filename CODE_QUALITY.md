# Code Quality

## Purpose

SOP for keeping code inside this repo's quality gates.

Sections 2 and 3 record this repo's Java toolchain — the gates that fail the
build and every threshold tuned away from its default. Everything else is
language-neutral and comes from the template.

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

## 2. Enforced gates

List every check that fails the build. A check that only warns is not a gate.

| gate | tool | scope | config |
|---|---|---|---|
| Format | Spotless (google-java-format) | all modules | `spotless` plugin block |
| Complexity + duplication | PMD + CPD (`failOnViolation=true`) | all modules | `aipersimmon-ddd-quality-config` → `pmd-ruleset.xml` |
| Bytecode defects | SpotBugs (`failOnError=true`) | all modules | `aipersimmon-ddd-quality-config` → `spotbugs-exclude.xml` |
| Coverage + mutation | JaCoCo + PIT | opt-in per domain module (`design-00007` §4.3) | per-module pom — bar per [TESTING.md](TESTING.md) |

Shared config lives in `aipersimmon-ddd-quality-config`. There is no shared
provider parent, so **two** build files carry the plugin block by hand and must
be changed together: the library parent pom, and the scaffold
`multi-module/pom.xml`.

## 3. Tuned thresholds (and why)

Start from the tool defaults. Record every deviation here **and** next to the
config itself, with the reason. A tuned value with no recorded rationale is
indistinguishable from a value that was raised to silence a failure.

| check | default | this repo | rationale |
|---|---|---|---|
| CognitiveComplexity | 15 | 15 | kept at the default — the real readability guard |
| CyclomaticComplexity | 10 | 15 | the accepted upper bound; over it → refactor, do not raise further |
| NPathComplexity | 200 | 200 | kept at the default; over it → refactor or suppress |
| ExcessiveParameterList | 10 | 18 | DI-heavy constructors and `@Bean` factories carry collaborators, not data |
| TooManyMethods | 10 | 30 | per-item classes: config `@Bean`, DAO-per-query, codec, state handlers |
| CPD minimumTokens | 100 | 250 library / 100 scaffold | library: MyBatis entity accessor boilerplate. Generated projects keep the strict default |

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

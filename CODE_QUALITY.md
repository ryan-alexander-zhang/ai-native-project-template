# CODE_QUALITY

SOP for keeping code within the quality gates. When a gate fails or a review flags
complexity/duplication: **solve it per this file. Never raise a threshold or suppress a
finding just to make a build pass.**

## 1. Metrics — what each catches

| metric | counts | flags | lever |
|---|---|---|---|
| Cyclomatic | decision points (`if`/loop/`case`/`&&`/`\|\|`/`?:`) | test & change risk (paths) | fewer branches |
| Cognitive | nesting depth + linear-flow breaks | reading difficulty | flatten + extract |
| NPath | product of independent branches | (multiplicative; noisy) | split method |
| ExcessiveParameterList | params | often DI, not data | param object / tune |
| TooManyMethods | methods | often per-item classes | split / tune |
| GodClass | WMC + ATFD + low cohesion (TCC) | several classes glued in one | Extract Class |
| CPD | duplicated tokens | copy-paste | DRY-extract |

Metrics are **review triggers, not design goals.** Cognitive complexity is the primary
readability guard; cyclomatic/NPath are secondary.

## 2. Enforced gates (fail the build)

| gate | tool | scope |
|---|---|---|
| Format | Spotless (google-java-format) | all |
| Complexity + duplication | PMD + CPD (`failOnViolation=true`) | all |
| Bytecode defects | SpotBugs (`failOnError=true`) | all |
| Coverage + mutation | JaCoCo + PIT | opt-in per domain module (design-00007 §4.3) |

Shared config lives in `aipersimmon-ddd-quality-config` (`pmd-ruleset.xml`,
`spotbugs-exclude.xml`). By D1 there is no shared provider parent, so the library parent
pom and the scaffold `multi-module/pom.xml` carry the plugin block **by hand, in sync.**

## 3. Tuned thresholds (and why)

| rule | value | rationale |
|---|---|---|
| CognitiveComplexity | 15 | default; the real readability guard — kept strict |
| CyclomaticComplexity | 15 | raised from PMD default 10 to the accepted upper bound; over → refactor, don't raise further |
| NPathComplexity | 200 | PMD default; over → refactor or suppress |
| ExcessiveParameterList | 18 | raised: DI-heavy constructors / `@Bean` factories carry collaborators, not data |
| TooManyMethods | 30 | raised: per-item classes (config `@Bean`, DAO-per-query, codec, state handlers) |
| CPD minimumTokens | 250 (library) / 100 (scaffold) | library: MyBatis entity accessor boilerplate; generated projects keep the strict default |

## 4. Refactoring levers (effect per metric)

| technique | cyclomatic | cognitive |
|---|---|---|
| Guard clause / early return | ~ | ↓↓ |
| Extract method (named step) | ↓ local | ↓ |
| Name a boolean condition | ~ | ↓ |
| Strategy / polymorphism (repeated type-switch) | ↓ caller | ↓ |
| Decision / transition table | ↓↓ | ↓↓ |
| Reduce mutable state (immutable, staged results) | ~ | ↓↓ |
| Extract Class (split a God Class by field-cluster) | ↓ | ↓ |

## 5. SOP — refactoring order (do not skip step 1)

1. **Test first.** Characterize current behavior + key branches with tests that pass on the *unchanged* code — the safety net. (Pure move/extract with full existing coverage may reuse it; state which tests cover the change.)
2. **Flatten nesting** — guard clauses / early return. Domain code throws a named error, never a silent `return`.
3. **Extract by business step** — names express intent (`priceAndValidateLines`, not `handle1`).
4. **Find the repeated decision dimension** (payment method, order state, level, channel).
5. **Pick the structure:** type-varying behavior → Strategy · state machine → transition table / State · finite input combos → decision table · standalone constraint → Specification · fixed sequence → Pipeline · God Class → Extract Class by cohesive field-cluster.
6. **Reduce shared mutable state** — immutable value objects, staged/derived results.
7. **Re-measure + human review.** Metric down ≠ better design.

## 6. Resolve → Tune → Suppress (strict priority)

1. **Solve (refactor).** Default. Extract the genuine outlier; do not design around the number.
2. **Tune a threshold.** Only when a high count is *inherent to a correct pattern* (DI ctors, DAO-per-query, config beans). Document the reason in `pmd-ruleset.xml`. **Never raise a threshold to swallow one outlier — refactor the outlier.**
3. **Suppress.** Only for framework boilerplate or a true false positive. Must be **visible + reasoned**: a `spotbugs-exclude.xml` entry, a CPD `minimumTokens` bump, or `@SuppressWarnings` with a comment. Prefer narrow/local over global.

## 7. Anti-patterns (never)

- Extract that only **relocates** nesting — complexity hidden, not removed.
- Class explosion to kill one small, stable `switch`.
- Boolean params selecting flows: `process(order, true, false)` → name the flows.
- Comments explaining complex code instead of simplifying it.
- Metric-as-goal: the number drops while rules scatter and the debug chain grows.

## 8. New vs legacy

Block **new** violations at the gate. Record legacy as debt and ratchet thresholds down
over time — do not mass-rewrite a working system in one pass.

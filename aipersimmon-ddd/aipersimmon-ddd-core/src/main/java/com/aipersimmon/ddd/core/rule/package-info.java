/**
 * Business invariants as first-class objects: {@link com.aipersimmon.ddd.core.rule.BusinessRule}
 * pairs a self-test with its own violation message and code, and
 * {@link com.aipersimmon.ddd.core.rule.BusinessRuleViolationException} is raised when an
 * aggregate checks a broken rule. This keeps invariants named, reusable, and
 * unit-testable instead of scattered across inline {@code if}/{@code throw} statements.
 */
package com.aipersimmon.ddd.core.rule;

/**
 * Business invariants as first-class objects: {@link com.aipersimmon.ddd.core.rule.Invariant} pairs
 * a self-test with its own violation message and code, and {@link
 * com.aipersimmon.ddd.core.rule.InvariantViolationException} is raised when an aggregate checks a
 * broken invariant. This keeps invariants named, reusable, and unit-testable instead of scattered
 * across inline {@code if}/{@code throw} statements.
 */
package com.aipersimmon.ddd.core.rule;

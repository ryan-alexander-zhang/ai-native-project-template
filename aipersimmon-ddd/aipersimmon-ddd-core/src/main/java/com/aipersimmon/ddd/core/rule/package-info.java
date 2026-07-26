/**
 * Business invariants as first-class objects: {@link com.aipersimmon.ddd.core.rule.Invariant} pairs
 * a self-test with its own violation message and code, and {@link
 * com.aipersimmon.ddd.core.rule.InvariantViolationException} is raised when an aggregate checks a
 * broken invariant. This keeps invariants named, reusable, and unit-testable instead of scattered
 * across inline {@code if}/{@code throw} statements.
 *
 * <p>{@link com.aipersimmon.ddd.core.rule.Specification} is the decision-style sibling: it answers
 * "does this match?" so a caller can branch, where an invariant answers "this must hold" so a write
 * can be refused. A model needs both — using one where the other belongs is what turns exceptions
 * into control flow, or lets an illegal state be written.
 */
package com.aipersimmon.ddd.core.rule;

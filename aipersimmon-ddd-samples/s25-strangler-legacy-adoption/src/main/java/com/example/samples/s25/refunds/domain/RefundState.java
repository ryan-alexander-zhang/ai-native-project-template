package com.example.samples.s25.refunds.domain;

/**
 * Three states, and they are the legacy column's three strings — deliberately.
 *
 * <p>The temptation when extracting an aggregate is to fix the vocabulary at the same time: rename {@code OPEN} to
 * {@code REQUESTED}, add {@code SETTLED}, drop {@code REJECTED} because nobody uses it. Every one of those is a data
 * migration on millions of rows, running at the same moment as a behaviour change, with the old code still writing.
 *
 * <p>So the first extraction keeps the strings and changes only where the rules live. Renaming is a later, separate,
 * boring migration — and by then it is a migration of one writer instead of two.
 */
public enum RefundState {
  OPEN,
  APPROVED,
  REJECTED;

  public boolean isClosed() {
    return this != OPEN;
  }
}

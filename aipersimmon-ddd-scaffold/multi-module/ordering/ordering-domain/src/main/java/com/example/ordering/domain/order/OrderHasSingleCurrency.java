package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Invariant: every line of an order prices itself in the same currency — an order has one total,
 * and a total across currencies does not exist.
 *
 * <p>The rule was always true here, but only as an arithmetic side effect: {@code Order#total()}
 * reducing mixed-currency lines happened to trip {@code Money}'s same-currency check, which throws
 * a codeless "currency mismatch" naming neither the order nor the rule. A rule the aggregate relies
 * on deserves a name, a stable code, and a place in the aggregate's own invariant list — the same
 * standing {@link OrderHasDistinctSkus} has. Like that one it spans all lines, so it is checked on
 * the {@link Order} root, not per line.
 */
record OrderHasSingleCurrency(List<OrderLine> lines) implements Invariant {

  @Override
  public boolean isBroken() {
    if (lines == null) {
      return false;
    }
    return lines.stream()
            .map(line -> line.unitPrice().currency())
            .collect(Collectors.toSet())
            .size()
        > 1;
  }

  @Override
  public String message() {
    return "an order's lines must share a single currency";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.MIXED_CURRENCY;
  }
}

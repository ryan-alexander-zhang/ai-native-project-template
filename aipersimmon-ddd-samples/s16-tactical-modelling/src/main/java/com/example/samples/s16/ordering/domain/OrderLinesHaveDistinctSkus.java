package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The same sku twice is two lines that should have been one. */
record OrderLinesHaveDistinctSkus(List<OrderLine> lines) implements Invariant {

  @Override
  public boolean isBroken() {
    Set<Sku> seen = new HashSet<>();
    return lines.stream().map(OrderLine::sku).anyMatch(sku -> !seen.add(sku));
  }

  @Override
  public String message() {
    return "an order must not repeat a sku across lines";
  }

  @Override
  public ErrorCode errorCode() {
    return OrderingErrorCode.ORDER_LINES_DUPLICATE_SKU;
  }
}

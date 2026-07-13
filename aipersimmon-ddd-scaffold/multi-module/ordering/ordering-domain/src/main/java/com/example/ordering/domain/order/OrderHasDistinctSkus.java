package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Invariant: the same SKU must not appear on more than one line — quantities should be
 * consolidated onto a single line instead. This spans all lines, so it is an
 * aggregate-level invariant on the {@link Order} root, not a per-line check.
 */
record OrderHasDistinctSkus(List<OrderLine> lines) implements Invariant {

    @Override
    public boolean isBroken() {
        if (lines == null) {
            return false;
        }
        long distinct = lines.stream().map(OrderLine::sku).collect(Collectors.toSet()).size();
        return distinct < lines.size();
    }

    @Override
    public String message() {
        return "an order must not repeat a SKU across lines";
    }

    @Override
    public ErrorCode errorCode() {
        return OrderingErrorCode.DUPLICATE_SKU;
    }
}

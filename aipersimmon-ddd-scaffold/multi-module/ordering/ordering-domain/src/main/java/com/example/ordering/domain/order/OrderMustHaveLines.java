package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.BusinessRule;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;

/**
 * Invariant: an order must have at least one line. Expressed as a {@link BusinessRule}
 * so the aggregate enforces it with {@code checkRule(...)} and the violation carries a
 * stable error code.
 */
record OrderMustHaveLines(List<OrderLine> lines) implements BusinessRule {

    @Override
    public boolean isBroken() {
        return lines == null || lines.isEmpty();
    }

    @Override
    public String message() {
        return "an order needs at least one line";
    }

    @Override
    public ErrorCode errorCode() {
        return OrderingErrorCode.ORDER_EMPTY;
    }
}

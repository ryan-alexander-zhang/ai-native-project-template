package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.BusinessRule;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;

/**
 * Invariant: an order may not carry more than {@value #MAX_LINES} lines. This is an
 * aggregate-level rule (it spans all lines), so it belongs on the {@link Order} root
 * rather than on any single line.
 */
record OrderWithinLineLimit(List<OrderLine> lines) implements BusinessRule {

    static final int MAX_LINES = 100;

    @Override
    public boolean isBroken() {
        return lines != null && lines.size() > MAX_LINES;
    }

    @Override
    public String message() {
        return "an order may not exceed " + MAX_LINES + " lines";
    }

    @Override
    public ErrorCode errorCode() {
        return OrderingErrorCode.TOO_MANY_LINES;
    }
}

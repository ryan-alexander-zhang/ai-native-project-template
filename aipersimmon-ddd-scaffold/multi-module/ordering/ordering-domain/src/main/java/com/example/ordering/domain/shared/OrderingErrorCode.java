package com.example.ordering.domain.shared;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The ordering context's catalogue of stable, machine-readable error codes. Domain
 * and application code carries one of these on the exceptions it throws, so the code
 * is fixed where the error originates and travels unchanged to the API edge. It is a
 * pure {@link ErrorCode} (no HTTP or transport concern); the interface layer resolves
 * each code to a wire {@code ProblemDescriptor} (its category family, or a per-code
 * override in {@code OrderingProblemCatalog}).
 */
public enum OrderingErrorCode implements ErrorCode {

    CREDIT_EXCEEDED("ordering.credit-exceeded", ErrorCategory.DOMAIN_RULE),
    ORDER_EMPTY("ordering.order-empty", ErrorCategory.DOMAIN_RULE),
    TOO_MANY_LINES("ordering.too-many-lines", ErrorCategory.DOMAIN_RULE),
    DUPLICATE_SKU("ordering.duplicate-sku", ErrorCategory.DOMAIN_RULE),
    /** An ordered SKU cannot currently be offered by the inventory context (unknown or out of stock). */
    STOCK_UNAVAILABLE("ordering.stock-unavailable", ErrorCategory.DOMAIN_RULE),
    ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
    CUSTOMER_NOT_FOUND("ordering.customer-not-found", ErrorCategory.NOT_FOUND);

    private final String code;
    private final ErrorCategory category;

    OrderingErrorCode(String code, ErrorCategory category) {
        this.code = code;
        this.category = category;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}

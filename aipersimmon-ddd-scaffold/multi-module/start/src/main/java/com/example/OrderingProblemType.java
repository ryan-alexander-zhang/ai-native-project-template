package com.example;

import com.aipersimmon.ddd.web.error.ProblemType;
import com.example.ordering.domain.shared.OrderingErrorCode;

/**
 * The ordering context's web problem catalogue: it maps each domain
 * {@link OrderingErrorCode} to its RFC 9457 wire form (type URI, HTTP status, title
 * key). It lives in the bootstrap module because it joins a domain concern (the code)
 * to a web concern (the status) — the domain itself stays free of any web dependency.
 * Registered into the starter's registry via a {@code ProblemTypeCatalog} bean.
 */
public enum OrderingProblemType implements ProblemType {

    CREDIT_EXCEEDED(OrderingErrorCode.CREDIT_EXCEEDED, "/problems/credit-exceeded", 422,
            "ordering.credit-exceeded.title"),
    ORDER_EMPTY(OrderingErrorCode.ORDER_EMPTY, "/problems/order-empty", 422,
            "ordering.order-empty.title"),
    ORDER_NOT_FOUND(OrderingErrorCode.ORDER_NOT_FOUND, "/problems/order-not-found", 404,
            "ordering.order-not-found.title"),
    CUSTOMER_NOT_FOUND(OrderingErrorCode.CUSTOMER_NOT_FOUND, "/problems/customer-not-found", 404,
            "ordering.customer-not-found.title");

    private final OrderingErrorCode errorCode;
    private final String typeUri;
    private final int status;
    private final String titleKey;

    OrderingProblemType(OrderingErrorCode errorCode, String typeUri, int status, String titleKey) {
        this.errorCode = errorCode;
        this.typeUri = typeUri;
        this.status = status;
        this.titleKey = titleKey;
    }

    @Override
    public String code() {
        return errorCode.code();
    }

    @Override
    public String typeUri() {
        return typeUri;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String titleKey() {
        return titleKey;
    }
}

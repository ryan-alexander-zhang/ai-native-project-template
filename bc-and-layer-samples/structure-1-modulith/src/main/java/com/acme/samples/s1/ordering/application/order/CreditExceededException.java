package com.acme.samples.s1.ordering.application.order;

/** Expected domain failure: order total exceeds the customer's credit limit. */
public class CreditExceededException extends RuntimeException {
    public CreditExceededException(String message) {
        super(message);
    }
}

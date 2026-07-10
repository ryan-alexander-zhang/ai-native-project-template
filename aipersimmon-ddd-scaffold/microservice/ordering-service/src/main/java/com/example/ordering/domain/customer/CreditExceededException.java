package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.exception.DomainException;

/** Raised when an order's total exceeds the customer's credit limit. */
public class CreditExceededException extends DomainException {

    public CreditExceededException(String message) {
        super(message);
    }
}

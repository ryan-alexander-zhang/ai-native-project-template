package com.acme.samples.s2.ordering.application;

public class CreditExceededException extends RuntimeException {
    public CreditExceededException(String message) {
        super(message);
    }
}

package com.acme.samples.s3.ordering.app.order;

public class CreditExceededException extends RuntimeException {
    public CreditExceededException(String message) { super(message); }
}

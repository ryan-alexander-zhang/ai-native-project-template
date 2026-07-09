package com.acme.samples.s3.ordering.app.order;

/** Raised when the synchronous availability check rejects the order up front. */
public class StockUnavailableException extends RuntimeException {
    public StockUnavailableException(String message) { super(message); }
}

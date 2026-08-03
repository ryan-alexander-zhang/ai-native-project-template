package com.example.samples.s19.ordering.application;

/**
 * An advisory query into another context. In production this is a remote call, which is the whole
 * reason the check that uses it must not run inside the write transaction.
 */
public interface CustomerStanding {

  boolean isBlocked(String customerId);
}

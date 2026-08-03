package com.example.samples.s03.ordering.application;

/**
 * An outbound side effect that must not happen for a write that never committed — which is why its
 * subscriber runs after commit rather than inside the transaction.
 */
public interface CustomerNotifier {

  void orderConfirmedTo(String customerId, String orderId);
}

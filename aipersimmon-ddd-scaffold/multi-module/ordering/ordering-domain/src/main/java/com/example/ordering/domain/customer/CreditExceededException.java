package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.OrderingErrorCode;

/**
 * Raised when an order's total exceeds the customer's credit limit. It carries the {@link
 * OrderingErrorCode#CREDIT_EXCEEDED} code so the interface layer renders a 422 problem response
 * with a stable machine-readable code.
 */
public class CreditExceededException extends DomainException {

  public CreditExceededException(String message) {
    super(OrderingErrorCode.CREDIT_EXCEEDED, message);
  }
}

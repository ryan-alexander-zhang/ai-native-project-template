package com.example.payment.domain;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * The outcome of assessing a payment authorization: a closed set of two cases. {@link Declined}
 * carries the stable code and reason that will ride the outbound {@code PaymentDeclined} event, so
 * the reacting saga has a machine identity to branch on.
 */
public sealed interface PaymentDecision {

  boolean isAuthorized();

  record Authorized() implements PaymentDecision {
    @Override
    public boolean isAuthorized() {
      return true;
    }
  }

  record Declined(String code, String reason) implements PaymentDecision {
    public Declined {
      if (code == null || code.isBlank()) {
        throw new DomainException("a declined decision must carry a code");
      }
    }

    @Override
    public boolean isAuthorized() {
      return false;
    }
  }
}

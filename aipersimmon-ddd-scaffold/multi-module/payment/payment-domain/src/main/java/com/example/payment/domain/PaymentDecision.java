package com.example.payment.domain;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * The recorded outcome of a payment operation: a closed set of three cases. {@link Declined}
 * carries the stable code and reason that will ride the outbound {@code PaymentDeclined} event, so
 * the reacting process manager has a machine identity to branch on.
 *
 * <p>{@link Voided} is the compensation outcome: the ordering flow abandoned its wait for this
 * operation (a timeout, or a cancellation racing the authorization) and asked for it to be undone.
 * Recorded against an operation nothing has decided yet, it is a refusal in advance — an
 * authorization arriving afterwards finds it and does not authorize. Recorded over an {@code
 * Authorized}, it is the hold's release. Either way it is terminal: a voided operation never
 * authorizes.
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

  record Voided() implements PaymentDecision {
    @Override
    public boolean isAuthorized() {
      return false;
    }
  }
}

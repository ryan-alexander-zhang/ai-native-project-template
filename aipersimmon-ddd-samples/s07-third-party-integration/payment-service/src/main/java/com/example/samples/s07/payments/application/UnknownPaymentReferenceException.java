package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.example.samples.s07.payments.domain.PaymentsErrorCode;

/**
 * A gateway notification named a payment this service has no record of.
 *
 * <p>It happens: a callback for a test transaction someone made in the provider's dashboard, a
 * misconfigured callback URL shared between two environments, a payment whose row was never committed
 * because the request died before the transaction did. What must not happen is inventing the payment
 * from the notification — the notification says nothing about the amount, the order, or whether we ever
 * intended to charge anything.
 */
public class UnknownPaymentReferenceException extends ApplicationException {

  public UnknownPaymentReferenceException(String reference) {
    super(
        PaymentsErrorCode.UNKNOWN_PAYMENT_REFERENCE,
        "no payment with reference '" + reference + "' exists in this service");
  }
}

package com.example.samples.s06.ordering.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.example.samples.s06.ordering.domain.OrderingErrorCode;

/**
 * No answer could be obtained.
 *
 * <p>An {@link ApplicationException} rather than a domain exception, because nothing about the domain went
 * wrong: the order might be perfectly acceptable and nobody knows. Keeping it separate from
 * {@link RiskRejectedException} is what lets the edge tell a client "try again" for one and "this was
 * refused" for the other — and lets a dashboard count them apart, which matters because one of them is an
 * incident and the other is Tuesday.
 */
public class RiskUnavailableException extends ApplicationException {

  public RiskUnavailableException(String message, Throwable cause) {
    super(OrderingErrorCode.RISK_UNAVAILABLE, message, cause);
  }
}

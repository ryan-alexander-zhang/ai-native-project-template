package com.example.samples.s06.ordering.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.example.samples.s06.ordering.domain.OrderingErrorCode;

/**
 * The risk service assessed this order and said no.
 *
 * <p>Thrown by the precheck, so it refuses the command <em>before</em> the transaction opens: the answer
 * is a precondition of doing the work, not a result of it.
 *
 * <p>It carries the callee's reason as detail but not the callee's error code: a client of this service
 * must not have to learn the risk service's vocabulary to handle a rejected order. Passing the upstream's
 * codes through is the most common way a "microservice boundary" quietly becomes a shared namespace.
 */
public class RiskRejectedException extends ApplicationException {

  public RiskRejectedException(String reason) {
    super(OrderingErrorCode.RISK_REJECTED, "risk assessment refused this order: " + reason);
  }
}

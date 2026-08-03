package com.example.samples.s06.risk.domain;

import com.aipersimmon.ddd.core.annotation.Service;
import java.util.Set;

/**
 * The rule, and the only thing in this service worth calling a domain model.
 *
 * <p>A decision service has no aggregate — there is no state whose invariants need protecting — so the
 * domain is a <strong>domain service</strong>: stateless behaviour that belongs to no entity. Modelling
 * it as one, rather than as a method on a controller or a handler, is what keeps the rule testable
 * without HTTP and reusable if a second entry point ever needs it.
 *
 * <p>Its data arrives through the constructor rather than being read from configuration here, so the
 * rule stays free of Spring and of the deployment. Whoever wires it decides where the numbers come from.
 */
@Service
public class RiskPolicy {

  private final long maxAmountCents;
  private final Set<String> blockedCustomers;

  public RiskPolicy(long maxAmountCents, Set<String> blockedCustomers) {
    this.maxAmountCents = maxAmountCents;
    this.blockedCustomers = Set.copyOf(blockedCustomers);
  }

  public RiskDecision decide(String customerId, long amountCents) {
    if (blockedCustomers.contains(customerId)) {
      return RiskDecision.reject("customer is blocked");
    }
    if (amountCents > maxAmountCents) {
      return RiskDecision.reject(
          "amount " + amountCents + " exceeds the unattended limit of " + maxAmountCents);
    }
    return RiskDecision.approve();
  }
}

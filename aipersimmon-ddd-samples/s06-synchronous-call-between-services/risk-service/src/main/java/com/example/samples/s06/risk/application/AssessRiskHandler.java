package com.example.samples.s06.risk.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s06.risk.domain.RiskDecision;
import com.example.samples.s06.risk.domain.RiskPolicy;
import org.springframework.stereotype.Component;

/** Delegates to the rule. There is nothing else for it to do, which is the right amount. */
@Component
class AssessRiskHandler implements QueryHandler<AssessRisk, RiskDecision> {

  private final RiskPolicy policy;

  AssessRiskHandler(RiskPolicy policy) {
    this.policy = policy;
  }

  @Override
  public RiskDecision handle(AssessRisk query) {
    return policy.decide(query.customerId(), query.amountCents());
  }
}

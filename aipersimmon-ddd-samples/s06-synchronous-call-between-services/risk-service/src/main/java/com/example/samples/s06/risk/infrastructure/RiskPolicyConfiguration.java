package com.example.samples.s06.risk.infrastructure;

import com.example.samples.s06.risk.domain.RiskPolicy;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the rule's numbers come from.
 *
 * <p>A service with no database still has infrastructure: configuration is an adapter like any other.
 * Keeping the {@code @Value} lookups here rather than in {@link RiskPolicy} is what lets the rule be
 * constructed in a unit test with two literals and no Spring.
 */
@Configuration(proxyBeanMethods = false)
class RiskPolicyConfiguration {

  @Bean
  RiskPolicy riskPolicy(
      @Value("${risk.max-amount-cents}") long maxAmountCents,
      @Value("${risk.blocked-customers:}") List<String> blockedCustomers) {
    return new RiskPolicy(maxAmountCents, Set.copyOf(blockedCustomers));
  }
}

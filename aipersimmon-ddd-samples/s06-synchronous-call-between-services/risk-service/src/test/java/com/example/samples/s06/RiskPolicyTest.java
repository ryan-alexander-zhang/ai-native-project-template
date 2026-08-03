package com.example.samples.s06;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s06.risk.domain.RiskDecision;
import com.example.samples.s06.risk.domain.RiskPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rule, tested at the cheapest layer that can answer — two literals and no Spring.
 *
 * <p>That is only possible because the policy takes its numbers through its constructor instead of
 * reading configuration itself. The same class with two {@code @Value} fields would have needed a context
 * to assert on arithmetic.
 */
class RiskPolicyTest {

  private final RiskPolicy policy = new RiskPolicy(100_000, Set.of("customer-blocked"));

  @Test
  void theLimitIsInclusive() {
    assertThat(policy.decide("customer-1", 100_000).approved()).isTrue();
    assertThat(policy.decide("customer-1", 100_001).approved()).isFalse();
  }

  @Test
  void ablockedCustomerIsRejectedRegardlessOfAmount() {
    RiskDecision decision = policy.decide("customer-blocked", 1);

    assertThat(decision.approved()).isFalse();
    assertThat(decision.reason()).isEqualTo("customer is blocked");
  }
}

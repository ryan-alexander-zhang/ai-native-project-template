package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ordering.domain.shared.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@link ManualReviewPolicy} verdict: an order that contains a restricted SKU is held for
 * review; any other order clears immediately. The rule is deliberately amount-independent.
 */
class ManualReviewPolicyTest {

  private final ManualReviewPolicy policy = new ManualReviewPolicy();

  private static LineData line(String sku) {
    return new LineData(sku, 1, Money.of(100, "USD"));
  }

  @Test
  void anOrdinaryOrderNeedsNoReview() {
    ReviewRequirement verdict = policy.assess(List.of(line("SKU-1"), line("SKU-2")));

    assertFalse(verdict.isRequired());
  }

  @Test
  void anOrderWithARestrictedSkuIsHeldForReviewWithAReason() {
    ReviewRequirement verdict = policy.assess(List.of(line("SKU-1"), line("SKU-RESTRICTED")));

    assertTrue(verdict.isRequired());
    ReviewRequirement.Required required = (ReviewRequirement.Required) verdict;
    assertTrue(
        required.reasons().stream().anyMatch(reason -> reason.contains("SKU-RESTRICTED")),
        "the review reason should name the restricted SKU");
  }

  @Test
  void anEmptyOrNullLineListNeedsNoReview() {
    assertFalse(policy.assess(List.of()).isRequired());
    assertFalse(policy.assess(null).isRequired());
  }
}

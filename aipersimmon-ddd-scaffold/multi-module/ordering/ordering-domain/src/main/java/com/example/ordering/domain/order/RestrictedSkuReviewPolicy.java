package com.example.ordering.domain.order;

import com.example.ordering.domain.shared.Sku;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The scaffold's default {@link ManualReviewPolicy}: hold any order containing a <em>restricted
 * SKU</em>.
 *
 * <p>Deliberately amount-independent, so it never entangles with the payment authorization ceiling
 * or a customer's credit limit (both of which are value-based). A real policy would consult a
 * fraud/compliance or product-classification service; this watchlist is the honest stand-in a
 * scaffold needs to make the review branch reachable from a test.
 *
 * <p>The watchlist arrives through the constructor rather than a {@code static final} field, which
 * is the whole difference from what this class used to be. It is now configurable ({@code
 * ordering.review.restricted-skus} in {@code application.yml}) <em>and</em> still framework-free —
 * this is a domain module, so it takes a {@code Set<Sku>} and knows nothing about where the values
 * came from. {@code OrderingPolicyConfig} in {@code start} does the binding.
 *
 * <p>Typed as {@code Set<Sku>} rather than {@code Set<String>} so the watchlist cannot be confused
 * with — or accidentally checked against — any other collection of strings this context holds
 * (issue-00085). Validation therefore happens at construction: a blank entry fails when its {@link
 * Sku} is built, at startup, rather than silently never matching at runtime.
 */
public final class RestrictedSkuReviewPolicy implements ManualReviewPolicy {

  private final Set<Sku> restrictedSkus;

  /**
   * @param restrictedSkus SKUs that force manual review; an empty set is legitimate and means "no
   *     order is ever held", which is what a deployment with no watchlist wants
   */
  public RestrictedSkuReviewPolicy(Set<Sku> restrictedSkus) {
    if (restrictedSkus == null) {
      throw new IllegalArgumentException(
          "restrictedSkus must not be null — pass an empty set to hold nothing");
    }
    this.restrictedSkus = Set.copyOf(restrictedSkus);
  }

  @Override
  public ReviewRequirement assess(List<LineData> lines) {
    Set<String> reasons = new LinkedHashSet<>();
    if (lines != null) {
      for (LineData line : lines) {
        if (restrictedSkus.contains(line.sku())) {
          reasons.add("restricted SKU requires manual review: " + line.sku());
        }
      }
    }
    return reasons.isEmpty()
        ? ReviewRequirement.notRequired()
        : ReviewRequirement.required(reasons);
  }
}

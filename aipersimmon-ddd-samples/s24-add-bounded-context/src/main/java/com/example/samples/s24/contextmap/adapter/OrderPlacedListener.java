package com.example.samples.s24.contextmap.adapter;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponRedemptions;
import com.example.samples.s24.ordering.api.OrderPlaced;
import com.example.samples.s24.sharedkernel.api.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ordering said an order was placed; coupons is told to count the redemption. The one class that knows both contexts.
 *
 * <p><strong>Why it is here and not in coupons.</strong> The usual arrangement — the downstream context owns its own
 * subscriber — would have put a dependency from {@code coupons} onto {@code ordering.api}, and ordering already depends
 * on {@code coupons.api} for the quote. That is a cycle between two published contracts.
 *
 * <p>The library's isolation rule would not object: it checks that a context is entered through its {@code api}, not that
 * the graph is acyclic. Nothing would fail. The build would keep working, because they are packages in one module — and it
 * would stop working on the first day somebody tried to make them two Maven modules, which is the day the cycle was always
 * going to matter. {@code ArchitectureTest.thecontextsFormNoCycle} is the rule that finds it now rather than then, and the
 * analysis document's negative controls measure that moving this class into {@code coupons} turns that rule red while
 * leaving the library's green.
 *
 * <p>Which makes this package the context map: where the relationships between contexts live, depending on both published
 * contracts and depended on by neither. It is also the package that disappears on the day the contexts are split — its
 * contents become a broker subscription in one service and an HTTP client in the other.
 *
 * <p><strong>It is an adapter, though the transport is in-process today.</strong> That is the library's own reasoning for
 * where an integration-event subscriber belongs, and this sample opts into the rule that enforces it. An event arrives
 * over a transport; the class that receives it translates and hands off inward, and nothing else about it changes when the
 * transport becomes real. What <em>will</em> change is everything around it — retries, dead letters, ordering — which is
 * why it is worth having it already sitting in the layer where those things live.
 *
 * <p>It holds no rule of its own. The refusal it can receive is not something it can fix, so it logs and stops: the order
 * is placed at a discounted price and the coupon would not take another redemption. Somewhere between "this must never
 * happen" and "this must be handled" is "this must be visible", and that is where a discrepancy belongs.
 */
@Component
class OrderPlacedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

  private final CouponRedemptions redemptions;

  OrderPlacedListener(CouponRedemptions redemptions) {
    this.redemptions = redemptions;
  }

  @EventListener
  void on(EventEnvelope<OrderPlaced> envelope) {
    OrderPlaced event = envelope.payload();
    if (event.couponCode() == null || event.couponCode().isBlank()) {
      return;
    }
    CouponRedemptions.Outcome outcome =
        redemptions.redeem(
            new CouponCode(event.couponCode()),
            event.orderId(),
            Money.of(event.discountMinor(), event.currency()));
    if (outcome == CouponRedemptions.Outcome.REFUSED) {
      log.warn(
          "order {} was discounted by {} {} with coupon {}, and the redemption was refused",
          event.orderId(),
          event.discountMinor(),
          event.currency(),
          event.couponCode());
    }
  }
}

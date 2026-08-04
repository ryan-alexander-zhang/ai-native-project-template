package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponQuote;
import com.example.samples.s24.coupons.api.CouponQuotes;
import com.example.samples.s24.sharedkernel.api.Money;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * When should the new context become its own deployment unit, and what has to change first?
 *
 * <p>The honest version of the question is the second half. "When" is a capacity and ownership decision no test can make.
 * "What has to change first" is a property of the code as it stands, it is measurable, and measuring it now — while the
 * fixes are cheap — is worth more than discovering it during the split.
 *
 * <p>Four answers:
 *
 * <ol>
 *   <li><strong>Nothing about the boundary itself.</strong> It is already an interface, so the implementation swaps. The
 *       test class proves it by replacing the whole coupons context with four lines;
 *   <li><strong>The transaction has to stop spanning the call.</strong> The quote happens inside ordering's write
 *       transaction today, because in-process that is free. Measured below;
 *   <li><strong>The caller needs a policy for "no answer".</strong> In-process the call cannot be unavailable; across a
 *       network it can. Measured below — and what happens today is that the order fails, which is right now and wrong
 *       after the split;
 *   <li><strong>No cycle, no shared table.</strong> Both already enforced, by {@code ArchitectureTest} and
 *       {@code TableOwnershipTest}. That is why they are the rules worth adding on the day the context is created rather
 *       than on the day it leaves.
 * </ol>
 *
 * <p>The coupons boundary is replaced here by a stub, which is itself the first answer: ordering does not notice.
 */
@Import(SplittingOutTest.SwappedOutCoupons.class)
class SplittingOutTest extends ContextTestBase {

  @Autowired private SwappedOutCoupons.StubbedQuotes stub;

  @AfterEach
  void disarm() {
    stub.reset();
  }

  /**
   * Ordering does not notice that the entire coupons context was replaced by four lines.
   *
   * <p>Which is the first answer to "what has to change": nothing, on this side of the interface. After the split the stub
   * is an HTTP client instead, and this test is what says that swap is possible without touching ordering.
   */
  @Test
  void thewholeCouponsContextCanBeSwappedOutWithoutOrderingNoticing() {
    var totals = placeOrder("ord-stub", 5_000, "ANY-CODE");
    assertThat(totals.discountMinor()).as("the stub's flat discount, not the real arithmetic").isEqualTo(1);
    assertThat(totals.couponCode()).isEqualTo("ANY-CODE");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s24_coupons_coupon", Integer.class))
        .as("no coupon was ever issued; the context was not consulted")
        .isZero();
  }

  /**
   * The quote runs inside the caller's write transaction. First thing to change on split day.
   *
   * <p>Not a bug today — in-process the call is a method invocation, and joining the caller's transaction costs nothing.
   * Across a network it becomes a remote call holding a database transaction open, which is the shape that turns one slow
   * dependency into a connection-pool outage (S28 measured what that costs). The fix is not to the interface: it is to
   * price the basket <em>before</em> opening the write transaction, a rearrangement of {@code PlaceOrderHandler} and of
   * nothing else.
   *
   * <p>Measured from inside the boundary, because that is the only place that can see the caller's transaction.
   */
  @Test
  void thequoteHappensInsideOrderingsWriteTransaction() {
    placeOrder("ord-tx", 5_000, "ANY-CODE");
    assertThat(stub.sawATransaction())
        .as("the boundary is crossed inside a write transaction — free now, a problem after the split")
        .isTrue();
  }

  /**
   * And today, no answer means no order.
   *
   * <p>Correct for an in-process call: an exception from a bean in the same JVM is a bug, not weather. Exactly wrong once
   * there is a network in the way, where unavailability is ordinary. The interface is already shaped for the fix —
   * {@code quote} returns a refusal rather than throwing, so a fallback has somewhere to live — but the policy itself does
   * not exist, and it is a business decision: does an unavailable coupons service mean "no discount" or "no order"?
   *
   * <p>The sample does not invent that decision. It measures that it has not been made, which is the useful thing to know
   * before the split rather than after.
   */
  @Test
  void anunavailableQuoteCurrentlyFailsTheWholeOrder() {
    stub.failNextQuote();
    assertThat(catchThrowable(() -> placeOrder("ord-down", 5_000, "ANY-CODE")))
        .as("no fallback exists, because in-process there was never anything to fall back from")
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM s24_ordering_order WHERE id = 'ord-down'", Integer.class))
        .as("and the order was not placed at all")
        .isZero();
  }

  /** An order that quotes no coupon is unaffected either way, which bounds the blast radius of all of the above. */
  @Test
  void anorderWithNoCouponIsUnaffectedByAnyOfThis() {
    stub.failNextQuote();
    var totals = placeOrder("ord-none", 5_000, null);
    assertThat(totals.discountMinor()).isZero();
    assertThat(orderRow("ord-none")).containsEntry("status", "PLACED");
  }

  /**
   * The coupons boundary, replaced.
   *
   * <p>Four lines of behaviour, and it satisfies ordering completely — which is the point being demonstrated rather than a
   * testing convenience. {@code @Primary} so ordering gets this one; the real implementation is still in the context and
   * still works, it is simply not what ordering is holding.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class SwappedOutCoupons {

    @Bean
    @Primary
    StubbedQuotes stubbedQuotes() {
      return new StubbedQuotes();
    }

    static class StubbedQuotes implements CouponQuotes {

      private final AtomicBoolean sawTransaction = new AtomicBoolean();
      private final AtomicBoolean failNext = new AtomicBoolean();

      boolean sawATransaction() {
        return sawTransaction.get();
      }

      void failNextQuote() {
        failNext.set(true);
      }

      void reset() {
        failNext.set(false);
        sawTransaction.set(false);
      }

      @Override
      public CouponQuote quote(CouponCode code, Money amount) {
        sawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        if (failNext.getAndSet(false)) {
          throw new IllegalStateException("the coupons service is unavailable");
        }
        return CouponQuote.accepted(code, Money.of(1, amount.currency()));
      }
    }
  }
}

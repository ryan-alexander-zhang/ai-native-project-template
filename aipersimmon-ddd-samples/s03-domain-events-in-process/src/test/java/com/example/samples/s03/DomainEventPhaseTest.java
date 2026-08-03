package com.example.samples.s03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s03.ordering.application.PlaceOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which transaction a reaction lands in, and what that costs — asserted rather than described.
 *
 * <p>Everything here needs a real transaction manager and a real commit, so this is a container test
 * by necessity (S18's layer 4). The publish guard is unit-tested instead, in
 * {@link PublishGuardTest}.
 */
@SpringBootTest
@Import({PostgresServiceConnection.class, RecordingNotifier.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class DomainEventPhaseTest {

  @Autowired private CommandBus commandBus;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RecordingNotifier.Notifier notifier;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s03_coupon");
    jdbc.update("DELETE FROM s03_order");
    notifier.reset();
  }

  @Test
  void anInTransactionSubscriberCommitsWithTheOrder() {
    String id = commandBus.send(new PlaceOrder("customer-1", true, 2500));

    // Two aggregates, one transaction: the coupon exists because the order does.
    assertThat(orderCount()).isEqualTo(1);
    assertThat(couponCountFor("customer-1")).isEqualTo(1);
    // And the after-commit subscriber ran, because the commit happened.
    assertThat(notifier.notified()).containsExactly("customer-1:" + id);
  }

  @Test
  void asubscriberThatIsNotInterestedDoesNothing() {
    commandBus.send(new PlaceOrder("customer-2", false, 2500));

    assertThat(orderCount()).isEqualTo(1);
    assertThat(couponCountFor("customer-2")).isZero();
  }

  @Test
  void afailingInTransactionSubscriberTakesTheOrderDownWithIt() {
    assertThatThrownBy(() -> commandBus.send(new PlaceOrder("poison-1", true, 2500)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the rewards context refused");

    // This is the whole point of the in-transaction phase: the order was never written. A reaction
    // that must not happen without the write, and a write that must not happen without the reaction,
    // belong in one transaction — and @EventListener is what puts them there.
    assertThat(orderCount()).isZero();
    assertThat(couponCountFor("poison-1")).isZero();
    // Nothing was announced either: AFTER_COMMIT never fires for a transaction that rolled back.
    assertThat(notifier.notified()).isEmpty();
  }

  @Test
  void afailingAfterCommitSubscriberLosesTheReactionAndKeepsTheWrite() {
    // The notifier throws for this customer. The command still succeeds.
    commandBus.send(new PlaceOrder("unreachable-1", true, 2500));

    // The order and the coupon are committed…
    assertThat(orderCount()).isEqualTo(1);
    assertThat(couponCountFor("unreachable-1")).isEqualTo(1);
    // …and the notification is simply gone. Nothing retried it, nothing recorded that it was owed,
    // and no exception reached the caller. This is what "in-process domain events are volatile" means
    // in practice, and the reason a reaction that may not vanish needs the outbox instead (S4).
    assertThat(notifier.notified()).isEmpty();
  }

  private long orderCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s03_order", Long.class);
  }

  private long couponCountFor(String customerId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM s03_coupon WHERE customer_id = ?", Long.class, customerId);
  }
}

package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What running three of these actually means, measured on one.
 *
 * <p>The interesting question about a scheduled relay in a multi-instance deployment is not "how do you
 * stop them colliding" — it is "what happens when one of them dies mid-poll", because that is what a
 * rolling deploy does to a third of your pods several times a week. The library answers it with a lease
 * on the row rather than a lock on the schedule, and the difference is the whole test below: with a lock
 * on the schedule, the dead instance's lock was held for its full duration and <em>no</em> instance
 * polled at all, so delivery stopped everywhere; with a lease on the row, only the rows that instance
 * had claimed are delayed, and only until the lease passes.
 *
 * <p>A dead instance is simulated by stamping a lease directly onto a row, which is exactly the state a
 * killed pod leaves behind: it cannot release anything on the way out, so its claim simply sits there.
 * No second JVM is needed to observe that, and one that was killed at the right microsecond would be a
 * test nobody could keep green.
 *
 * <p>The configuration this argues for is the plain one: leave {@code relay.enabled} on everywhere, let
 * every instance poll, and set {@code relay.lease-duration} for how fast a dead instance's rows should
 * come back — not for how slow a batch might be, since a poll bounds itself at half the lease. Pinning
 * the relay to one instance (a leader election, a singleton deployment) buys nothing here and costs the
 * only property that made delivery survive a restart.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aipersimmon.ddd.outbox.relay.enabled=false"})
@Import({PostgresServiceConnection.class, StrictKafka.class, ProvisionedTopic.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class MultiInstanceTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;

  @BeforeEach
  void reset() {
    Outbox.clear(jdbc);
  }

  /**
   * A row another instance holds is skipped, and comes back on its own once the lease expires. Nothing
   * detects the death; nothing has to.
   */
  @Test
  void arowHeldByADeadInstanceIsSkippedAndReturnsWhenItsLeaseExpires() {
    place("customer-1", "sku-keyboard", 2);
    leaseEverythingTo("dead-pod-7", Instant.now().plusSeconds(300));

    relay.relay();

    // Untouched: this poller can see the row and will not take it.
    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);

    // Time passes (the lease is what expires, not a heartbeat that stops arriving).
    leaseEverythingTo("dead-pod-7", Instant.now().minusSeconds(1));
    relay.relay();

    assertThat(Outbox.unsentCount(jdbc)).isZero();
  }

  /**
   * The rows one instance was holding are all that is delayed. Everything else keeps flowing.
   *
   * <p>This is the property that made the lease worth moving off the schedule, and it is invisible until
   * something is actually stuck: a deployment where a dead pod stops delivery for the whole fleet looks
   * identical to a healthy one until the day a pod dies.
   */
  @Test
  void onedeadInstanceDelaysItsOwnRowsAndNothingElse() {
    place("customer-1", "sku-keyboard", 2);
    leaseEverythingTo("dead-pod-7", Instant.now().plusSeconds(300));
    place("customer-2", "sku-mouse", 1);

    relay.relay();

    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT lease_owner FROM aipersimmon_outbox WHERE sent = FALSE", String.class))
        .isEqualTo("dead-pod-7");
  }

  /**
   * The relay takes no ShedLock lease at all, and the purge does.
   *
   * <p>Two components in one starter with opposite answers, which is the thing to understand before
   * configuring either. The relay wants every instance working, so it coordinates per row. The purge is
   * a bulk delete over the same table and gains nothing from running three times at once, so it takes a
   * lock — see {@code PurgeTest}, which asserts the row this one asserts the absence of.
   */
  @Test
  void therelayHoldsNoSchedulerLock() {
    place("customer-1", "sku-keyboard", 2);

    relay.relay();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock", Long.class)).isZero();
  }

  /**
   * Stamps a claim on every row that is currently waiting — the state a pod killed mid-poll leaves
   * behind, since it releases nothing on the way out.
   */
  private void leaseEverythingTo(String owner, Instant until) {
    jdbc.update(
        "UPDATE aipersimmon_outbox SET lease_owner = ?, lease_token = ?, lease_until = ?"
            + " WHERE sent = FALSE",
        owner,
        "token-" + owner,
        Timestamp.from(until));
  }

  private void place(String customerId, String sku, int quantity) {
    http.postForEntity(
        "/orders", Map.of("customerId", customerId, "sku", sku, "quantity", quantity), String.class);
  }
}

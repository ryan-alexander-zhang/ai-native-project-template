package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the purge deletes when a deployment does switch it on — and, more usefully, what it refuses to
 * touch.
 *
 * <p>Retention here is zero seconds and the sweep runs twice a second, which is a test fixture and not
 * advice. The real scheduled bean is used rather than a hand-built one, so what is measured includes the
 * ShedLock lease and the {@code @Scheduled} trigger.
 *
 * <p><strong>One sweep, three populations, two of them untouched.</strong> That is the design worth
 * seeing: the purge is defined over "sent, and older than the cutoff", so an undelivered message can
 * never be deleted by a retention setting, however aggressive — a retention that could drop unsent rows
 * would turn a storage knob into a data-loss knob. Dead letters are in a different table entirely and
 * are not swept at all, because their whole purpose is to outlive the incident that produced them.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.cleanup.enabled=true",
      "aipersimmon.ddd.outbox.cleanup.retention-seconds=0",
      "aipersimmon.ddd.outbox.cleanup.poll-delay-ms=500"
    })
@Import({PostgresServiceConnection.class, StrictKafka.class, ProvisionedTopic.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class PurgeTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
  @Autowired private ApplicationContext context;

  @Test
  void thesweepTakesTheDeliveredRowsAndLeavesTheRestAlone() {
    // The property is what put the bean there; RetentionTest is the other half of that claim.
    assertThat(context.getBeansOfType(OutboxCleanup.class)).hasSize(1);

    Outbox.clear(jdbc);

    // 1. Two delivered messages: purgeable.
    place("customer-1", "sku-keyboard", 2);
    place("customer-2", "sku-mouse", 1);
    relay.relay();
    assertThat(Outbox.unsentCount(jdbc)).isZero();

    // 2. One message set aside: a different table, and not the purge's business.
    Outbox.writeLeftoverRow(jdbc, "left-over-1", "order-gone");
    relay.relay();
    assertThat(Outbox.deadCount(jdbc)).isEqualTo(1);

    // 3. One message still waiting: never purgeable at any retention, because the predicate is
    //    "sent AND older than the cutoff" and this row is not sent.
    place("customer-3", "sku-cable", 4);
    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> Outbox.liveCount(jdbc) == 1);

    assertThat(Outbox.unsentCount(jdbc)).isEqualTo(1);
    assertThat(Outbox.deadCount(jdbc)).isEqualTo(1);

    // And the purge is the component that takes a scheduler lock, named after the application. Two
    // deployments sharing a database and a spring.application.name would take each other's lease and
    // each conclude the other was itself — which is why naming a service is not cosmetic.
    // MultiInstanceTest asserts the other half: the relay takes no such lock, on purpose.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = ?",
                Long.class,
                "s22-ordering-service-outbox-cleanup"))
        .isEqualTo(1);
  }

  private void place(String customerId, String sku, int quantity) {
    http.postForEntity(
        "/orders", Map.of("customerId", customerId, "sku", sku, "quantity", quantity), String.class);
  }
}

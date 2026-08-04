package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The third failure tier, and the one whose absence causes the worst incidents: the environment is down,
 * not the message.
 *
 * <p>A retry policy with one setting has to choose. Bounded retries then a dead-letter topic is right for
 * a bad record and catastrophic for a ten-minute database outage: the partition drains itself into the DLT
 * at retry speed, and what was a blip becomes a manual replay of everything that arrived during it, in
 * whatever order somebody replays it. Unbounded retries are right for the outage and catastrophic for a
 * bad record. So the library refuses to pick and classifies instead — and it only claims certainty for the
 * one signal that carries it, a {@code DataAccessException}.
 *
 * <p>The price is stated plainly in the library's own javadoc and is measured below: <strong>a systemically
 * failed record is retried forever and never dead-lettered, so the partition waits at it and consumes
 * nothing else until the cause is fixed.</strong> That is a deliberate stall — it preserves order and keeps
 * healthy messages out of the DLT — and it is only survivable because it self-heals, which is the last
 * assertion here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  StrictKafka.class,
  SourceTopicAndDlt.class,
  OutageSimulation.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class SystemicFailureTest extends ConsumerTestBase {

  @AfterEach
  void bringItBack() {
    OutageSimulation.DOWN.set(false);
  }

  @Test
  void anoutageStallsThePartitionWithoutDeadLetteringAnythingAndThenHealsItself() {
    // Control: the pipeline works while the environment is up.
    send(WireRecords.order(topic, "order-1", "sku-keyboard", 1));
    await().atMost(Duration.ofSeconds(20)).until(() -> reserved("sku-keyboard") == 1);

    OutageSimulation.DOWN.set(true);
    send(WireRecords.order(topic, "order-2", "sku-mouse", 1));
    send(WireRecords.order(topic, "order-3", "sku-cable", 1));

    // Several retry intervals pass (one second each, unbounded).
    await().pollDelay(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(10)).until(() -> true);

    // Nothing was handled, and — the point — nothing was thrown away either. The dead-letter topic exists
    // in this context, so its emptiness is a measurement rather than an accident of provisioning.
    assertThat(reserved("sku-mouse")).isZero();
    assertThat(reserved("sku-cable")).isZero();
    assertThat(drain(topic + ".DLT")).isEmpty();
    // No dedup record either: the delivery failed, so its inbox row rolled back with it. That is what
    // keeps the retry from being mistaken for a duplicate — an inbox consulted outside the handler's
    // transaction would be worse than none.
    assertThat(inboxCount()).isEqualTo(1);

    // The database comes back. Nobody replays anything, nobody restarts anything, nobody touches the
    // consumer group: the record was never lost, so recovery is just the next retry succeeding.
    OutageSimulation.DOWN.set(false);

    await().atMost(Duration.ofSeconds(30)).until(() -> reserved("sku-mouse") == 1);
    // And the one behind it, in order, because the partition never gave up its position.
    await().atMost(Duration.ofSeconds(30)).until(() -> reserved("sku-cable") == 1);
  }
}

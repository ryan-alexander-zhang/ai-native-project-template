package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Why the inbox's retention window is not a storage setting.
 *
 * <p>Every other framework table here holds records whose only cost is disk: purging a sent outbox row
 * loses history, and history is worth something, but nothing behaves differently afterwards. The inbox is
 * the exception, and the difference is worth stating precisely: <strong>an inbox row is not a record of
 * what happened, it is the thing that makes the next delivery a no-op.</strong> Delete it and the same
 * message is no longer a duplicate — it is a new message with the same content, and it will be handled
 * again, with whatever effect that has.
 *
 * <p>So the window is chosen from the longest path by which the same message could still arrive, and that
 * path is longer than it looks. It is not the broker's retention alone. It is the broker's retention, plus
 * a consumer group that gets reset to the beginning during a recovery, plus a dead letter an operator
 * replays a fortnight later, plus a publisher whose own outbox was stuck. The value has to exceed the
 * maximum of all of those — and the middle ones are invisible from the consuming side, which is exactly
 * why picking this number by looking at {@code retention.ms} is the mistake to avoid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresServiceConnection.class, StrictKafka.class, SourceTopicAndDlt.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class InboxRetentionTest extends ConsumerTestBase {

  @Test
  void aredeliveryIsFreeUntilItsInboxRowIsPurgedAndThenItIsADoubleReservation() {
    String eventId = UUID.randomUUID().toString();

    send(WireRecords.order(topic, "order-1", "sku-keyboard", 3, eventId));
    await().atMost(Duration.ofSeconds(20)).until(() -> reserved("sku-keyboard") == 3);
    assertThat(inboxCount()).isEqualTo(1);

    // The identical message again — same ce_source, same ce_id. This is what at-least-once delivery
    // routinely produces: the publisher's relay crashed between a successful send and marking the row
    // sent, or somebody replayed a dead letter.
    send(WireRecords.order(topic, "order-1", "sku-keyboard", 3, eventId));

    await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).until(() -> true);
    assertThat(reserved("sku-keyboard")).isEqualTo(3);

    // Now the retention window has passed for that key. Deleted directly rather than by enabling the purge
    // with a zero window: the claim is about the WINDOW, and a background job racing the consumer would
    // make the same point non-deterministically. The resulting state is identical — the key is gone.
    jdbc.update("DELETE FROM aipersimmon_inbox");

    send(WireRecords.order(topic, "order-1", "sku-keyboard", 3, eventId));

    // Handled a second time. Six reserved for an order of three, no error anywhere, and nothing in the
    // logs to suggest a problem — the message was, as far as this service can now tell, new.
    await().atMost(Duration.ofSeconds(20)).until(() -> reserved("sku-keyboard") == 6);
    assertThat(inboxCount()).isEqualTo(1);
  }
}

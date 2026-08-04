package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The whole runbook, in the order it actually happens: publish into nothing, see the give-up, fix the
 * cause, replay, watch it arrive.
 *
 * <p>The fix is the real fix — the topic gets created, with an {@code Admin} client, mid-test. That is
 * the only step of an incident nobody can automate, and putting it in the middle of one test is what
 * makes the two halves either side of it mean anything: before it, replay is futile; after it, replay
 * is sufficient and nothing else has to be touched.
 *
 * <p>One test method, on purpose. The two states of the world (topic absent, topic present) differ by
 * an irreversible act on shared infrastructure, so splitting them into two methods would make the
 * outcome depend on the order JUnit happened to pick.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "ordering.events-topic=s22.ordering.provisioned-late"
    })
@Import({PostgresServiceConnection.class, StrictKafka.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class ReplayAfterTheFixTest {

  private static final String TOPIC = "s22.ordering.provisioned-late";

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
  @Autowired private KafkaConnectionDetails kafka;

  @Test
  void theeventGoesOutOnReplayOnceTheTopicExistsAndKeepsItsOriginalIdentity() {
    Outbox.clear(jdbc);
    http.postForEntity(
        "/orders", Map.of("customerId", "customer-1", "sku", "sku-keyboard", "quantity", 2),
        String.class);

    // 1. It cannot be delivered, and after three attempts it is set aside.
    await()
        .atMost(Duration.ofSeconds(90))
        .until(
            () -> {
              relay.relay();
              return Outbox.deadCount(jdbc) == 1;
            });
    String eventId = (String) Outbox.deadRows(jdbc).get(0).get("event_id");

    // 2. The operator fixes the actual cause. Nothing about the service is redeployed or reconfigured:
    //    the row already knows where it was going, because the destination was resolved in the
    //    transaction that wrote it and stored on the row.
    createTopic();

    // 3. Replay, through the endpoint an operator has.
    assertThat(
            http.postForEntity("/ops/dead-letters/" + eventId + "/replay", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    relay.relay();

    // 4. It is on the topic, once, and the dead-letter table is empty.
    List<ConsumerRecord<String, String>> records = drain();
    assertThat(records).hasSize(1);
    assertThat(Outbox.deadCount(jdbc)).isZero();
    assertThat(Outbox.unsentCount(jdbc)).isZero();

    // And this is what makes replay safe to press: the event keeps the id it was born with, so a
    // consumer that had somehow already seen it recognises the duplicate through its inbox — the same
    // (source, ce_id) key that absorbs the relay's own at-least-once redeliveries. A replay that minted
    // a fresh id would be a second event about the same fact, and no downstream dedup could catch it.
    assertThat(header(records.get(0), "ce_id")).isEqualTo(eventId);
    assertThat(header(records.get(0), "ce_type"))
        .isEqualTo("com.example.samples.ordering.OrderPlaced");
  }

  /** Creates the destination topic — the fix, done the way an operator would. */
  private void createTopic() {
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers())))) {
      admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("could not create " + TOPIC, e);
    }
  }

  private List<ConsumerRecord<String, String>> drain() {
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "replay-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
            new StringDeserializer(),
            new StringDeserializer())) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
      for (int emptyPolls = 0; emptyPolls < 3; ) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
        if (polled.isEmpty()) {
          emptyPolls++;
          continue;
        }
        emptyPolls = 0;
        polled.records(TOPIC).forEach(collected::add);
      }
      return collected;
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value());
  }
}

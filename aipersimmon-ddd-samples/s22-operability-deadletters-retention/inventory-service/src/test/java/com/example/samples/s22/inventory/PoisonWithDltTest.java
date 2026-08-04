package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The same poison record, one topic more provisioned, and the opposite outcome.
 *
 * <p>Nothing about the application changes between this class and {@link PoisonWithoutDltTest} — same
 * code, same configuration, same record. The only difference is that {@code <topic>.DLT} exists, and it
 * turns a permanent stall into a quarantined record and a partition that keeps working. That is the
 * entire operational value of provisioning one extra topic, and it is why "we will add the DLT when we
 * need it" is exactly backwards: the moment you need it is the moment you cannot get to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresServiceConnection.class, StrictKafka.class, SourceTopicAndDlt.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class PoisonWithDltTest extends ConsumerTestBase {

  @Test
  void thepoisonIsQuarantinedAndThePartitionCarriesOn() {
    send(WireRecords.unknownType(topic, "order-2"));
    send(WireRecords.order(topic, "order-3", "sku-mouse", 1));

    // The record behind the poison is processed, which is the whole point.
    await().atMost(Duration.ofSeconds(30)).until(() -> reserved("sku-mouse") == 1);

    List<ConsumerRecord<String, String>> quarantined = drain(topic + ".DLT");
    assertThat(quarantined).hasSize(1);
    ConsumerRecord<String, String> record = quarantined.get(0);

    // The record arrives whole: the CloudEvents attributes it came with are still on it, so a later
    // build that understands this type can replay it from here. A dead-letter topic that stripped the
    // headers would hold bytes nobody can route.
    assertThat(header(record, "ce_type"))
        .isEqualTo("com.example.samples.ordering.OrderRenamedIntoTheFuture");
    assertThat(header(record, "ce_id")).isNotBlank();
    // And the key is preserved, so one aggregate's dead letters stay together on the DLT even though the
    // recoverer deliberately does not copy the source partition number.
    assertThat(record.key()).isEqualTo("order-2");
    // Spring's recoverer adds its own diagnostics — this is where the exception actually is, and it is
    // the reason a DLT beats a "gave up" log line: the evidence travels with the record.
    assertThat(header(record, "kafka_dlt-exception-message")).isNotBlank();

    // A poison record is a record that was never handled, so it must not be in the dedup table: if it
    // were, the later build that finally understands the type would replay it and be told it is a
    // duplicate. The inbox records what was PROCESSED, not what was received.
    assertThat(inboxCount()).isEqualTo(1);
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value());
  }
}

package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The failure this whole module exists for: a bad record on a topic whose {@code .DLT} nobody created.
 *
 * <p>The record cannot be handled, so after its bounded retries the error handler hands it to the
 * recoverer, which publishes it to {@code <topic>.DLT}. That publish fails, because the topic is not
 * there. A recoverer that fails means the record was not recovered, so the container seeks back and
 * delivers it again — and again. The partition stops advancing, permanently, and every healthy message
 * behind it waits.
 *
 * <p>What makes this the worst failure in the sample is not the stall. It is that nothing says so in a
 * way anyone is watching: the service is up, its health probes pass, its consumer group exists, and lag
 * grows on one partition. The library logs an ERROR from the recovery path each cycle, which is real but
 * is one line in a log nobody is tailing. <strong>A consumer's dead-letter topic has to be provisioned
 * with the topic itself, and consumer lag has to be alerted on per partition, because that pair is the
 * only thing that turns this into a page.</strong>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresServiceConnection.class, StrictKafka.class, SourceTopicOnly.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class PoisonWithoutDltTest extends ConsumerTestBase {

  /**
   * Two records, one partition, and the second one never arrives.
   *
   * <p>The good record sent first is the control. Without it, "the second record was not processed" is
   * satisfied by a consumer that is not working at all, and this test would pass on an application that
   * consumes nothing.
   */
  @Test
  void apoisonRecordStopsThePartitionWhenThereIsNowhereToPutIt() {
    // Control: the pipeline works.
    send(WireRecords.order(topic, "order-1", "sku-keyboard", 1));
    await().atMost(Duration.ofSeconds(20)).until(() -> reserved("sku-keyboard") == 1);

    // The dead-letter topic is not there. Nobody created it, because its name is in no configuration
    // file — the error handler derives it from the source topic.
    assertThat(topicExists(topic + ".DLT")).isFalse();

    send(WireRecords.unknownType(topic, "order-2"));
    send(WireRecords.order(topic, "order-3", "sku-mouse", 1));

    // Everything behind the poison waits. Long enough to be sure this is a stall and not slowness: the
    // bounded retry schedule is under two seconds, and each failed recovery publish costs the producer's
    // max.block.ms (five seconds here, sixty by default).
    await().pollDelay(Duration.ofSeconds(20)).atMost(Duration.ofSeconds(25)).until(() -> true);

    assertThat(reserved("sku-mouse")).isZero();
    // Still nowhere to put it, and still nothing consumed past it.
    assertThat(topicExists(topic + ".DLT")).isFalse();
  }
}

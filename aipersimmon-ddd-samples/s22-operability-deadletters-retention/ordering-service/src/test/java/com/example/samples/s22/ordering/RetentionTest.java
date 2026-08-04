package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the library does about the four framework tables when nobody tells it to: nothing.
 *
 * <p>This class carries no {@code cleanup} properties, so it measures the shipped default rather than a
 * configuration. The default is off, and that is the right default for a reason worth stating plainly:
 * deleting rows is an irreversible act on a consumer's data, and the correct retention is a property of
 * the deployment (how far back does anyone need to reread what was published, how long may a broker
 * redeliver) that no library can know. A framework that swept by default would eventually delete
 * something someone needed and would be right about it in the abstract.
 *
 * <p>The cost of the same default is this: a service that never sets it grows a table forever, and the
 * first person to notice is a DBA. So "off by default" is only a good default when it comes with
 * something that makes the omission visible — which for these tables is a metric or a dashboard, and is
 * genuinely the gap S22 cannot close from inside a sample.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aipersimmon.ddd.outbox.relay.enabled=false"})
@Import({PostgresServiceConnection.class, StrictKafka.class, ProvisionedTopic.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class RetentionTest {

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRelay relay;
  @Autowired private ApplicationContext context;

  @BeforeEach
  void reset() {
    Outbox.clear(jdbc);
  }

  /** No property, no bean, no purge. The condition is {@code havingValue = "true"} with no default. */
  @Test
  void nothingSweepsAnythingUntilADeploymentSaysSo() {
    assertThat(context.getBeansOfType(OutboxCleanup.class)).isEmpty();
  }

  /**
   * A delivered message stays in the table, flagged sent.
   *
   * <p>Which is the useful behaviour and the growth problem at once. Useful, because "what did we
   * publish last Tuesday" is answerable from the outbox and from nowhere else — the broker's retention
   * is shorter and is not yours. A problem, because the row is on the write-hot table every command
   * inserts into, and its indexes are paid for by the business transaction.
   */
  @Test
  void adeliveredMessageStaysUntilSomethingDeletesIt() {
    place("customer-1", "sku-keyboard", 2);
    place("customer-2", "sku-mouse", 1);

    relay.relay();

    assertThat(Outbox.unsentCount(jdbc)).isZero();
    assertThat(Outbox.liveCount(jdbc)).isEqualTo(2);
  }

  private void place(String customerId, String sku, int quantity) {
    http.postForEntity(
        "/orders", Map.of("customerId", customerId, "sku", sku, "quantity", quantity), String.class);
  }
}

package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.inbox.Inbox;
import com.aipersimmon.ddd.testsupport.KafkaServiceConnection;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The inbox's contract, in isolation: first call records and says "go ahead", second call says
 * "already done".
 *
 * <p>Written because the end-to-end consumption produced a state no other hypothesis explains — the
 * message consumed, an inbox row written, the stock untouched, and no exception anywhere. If the
 * return value means the opposite of what the handler assumes, that is exactly what it would look
 * like. The annotation set is copied verbatim from {@code InboxConsumptionTest} so the two share one
 * context and one container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresServiceConnection.class,
  KafkaServiceConnection.class,
  TestKafkaTopics.class,
  ProbeDispatch.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class InboxSemanticsTest {

  @Autowired private Inbox inbox;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theFirstCallProceedsAndTheSecondReportsADuplicate() {
    jdbc.update("DELETE FROM aipersimmon_inbox");
    String source = "/ordering";
    String messageKey = UUID.randomUUID().toString();

    boolean first = inbox.alreadyProcessed(source, messageKey);
    boolean second = inbox.alreadyProcessed(source, messageKey);

    assertThat(first).as("first delivery: false means 'this call recorded it, proceed'").isFalse();
    assertThat(second).as("redelivery: true means 'already handled, skip'").isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class))
        .isEqualTo(1);
  }
}

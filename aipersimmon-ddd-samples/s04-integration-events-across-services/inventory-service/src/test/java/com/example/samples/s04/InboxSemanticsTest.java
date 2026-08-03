package com.example.samples.s04;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.inbox.Inbox;
import com.aipersimmon.ddd.tenancy.MissingTenantException;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
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
  Probes.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class InboxSemanticsTest {

  private static final TenantId TENANT = Tenants.of("acme");

  @Autowired private Inbox inbox;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theFirstCallProceedsAndTheSecondReportsADuplicate() {
    jdbc.update("DELETE FROM aipersimmon_inbox");
    String source = "/ordering";
    String messageKey = UUID.randomUUID().toString();

    // runAs, because the inbox stamps its row with TenantContext.effective() and this is a bare test
    // thread. In production the caller is the consumer bridge, which binds the tenant from ce_tenantid
    // before touching the database — the port has no tenant parameter precisely so that no caller can
    // pass the wrong one.
    boolean first = TenantContext.runAs(TENANT, () -> inbox.alreadyProcessed(source, messageKey));
    boolean second = TenantContext.runAs(TENANT, () -> inbox.alreadyProcessed(source, messageKey));

    assertThat(first).as("first delivery: false means 'this call recorded it, proceed'").isFalse();
    assertThat(second).as("redelivery: true means 'already handled, skip'").isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT tenant_id FROM aipersimmon_inbox", String.class))
        .as("the dedup row carries its tenant as stamped data, not as a query predicate")
        .isEqualTo(TENANT.value());
  }

  @Test
  void theSamePortOnATenantLessThreadFailsClosed() {
    // The same call with nothing bound. With tenancy enabled this throws rather than stamping the
    // __root__ sentinel: a dedup row filed under the wrong tenant would either suppress a real message
    // or fail to suppress a duplicate, and neither is detectable afterwards.
    assertThatThrownBy(() -> inbox.alreadyProcessed("/ordering", UUID.randomUUID().toString()))
        .isInstanceOf(MissingTenantException.class);
  }
}

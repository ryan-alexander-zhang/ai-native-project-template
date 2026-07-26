package com.aipersimmon.ddd.inbox.jdbc;

import com.aipersimmon.ddd.inbox.Inbox;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records handled message keys in the inbox table, scoped to a configured {@code consumer} (this
 * application's identity), so several services sharing one inbox table do not suppress one
 * another's processing of the same producer-assigned message id.
 *
 * <p>It checks for the key first and only inserts when absent. Doing the read first keeps the
 * common redelivery case — the key is already recorded — free of a constraint violation, which
 * matters on PostgreSQL where a failed insert marks the whole transaction as aborted and would then
 * fail the surrounding commit. The unique key still guards the rare race of two simultaneous
 * first-time deliveries: the losing insert fails and its transaction rolls back, so the message is
 * simply redelivered and then detected as already processed.
 *
 * <p>Runs in the caller's transaction, so the record commits and rolls back together with the
 * processing.
 */
public class JdbcInbox implements Inbox {

  private static final String EXISTS =
      "SELECT COUNT(*) FROM aipersimmon_inbox WHERE consumer = ? AND message_key = ?";
  private static final String INSERT =
      "INSERT INTO aipersimmon_inbox (consumer, message_key, tenant_id, processed_at)"
          + " VALUES (?, ?, ?, ?)";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final String consumer;

  public JdbcInbox(JdbcTemplate jdbc, Clock clock, String consumer) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.consumer = consumer;
  }

  @Override
  public boolean alreadyProcessed(String messageKey) {
    Integer count = jdbc.queryForObject(EXISTS, Integer.class, consumer, messageKey);
    if (count != null && count > 0) {
      return true;
    }
    // The tenant is bound ambiently by the consume boundary (e.g. the Kafka listener's runAs);
    // absent that, a single-tenant caller records the root sentinel. Data column only — dedup is
    // still keyed by (consumer, message_key).
    String tenant = TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value());
    jdbc.update(INSERT, consumer, messageKey, tenant, Timestamp.from(clock.instant()));
    return false;
  }
}

package com.aipersimmon.ddd.processmanager.jdbc.lease;

import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The JDBC implementation of {@link ProcessClaimStrategy}. It closes over the {@link JdbcTemplate},
 * the database-specific {@link JdbcProcessDialect} (SKIP LOCKED vs. atomic conditional UPDATE), and
 * this worker's {@link WorkerId}, so the engine's relay and deadline worker claim due work through
 * the persistence-agnostic port without seeing any of them. Each claim runs inside the transaction
 * the caller opened through the {@code ProcessUnitOfWork}.
 */
public final class JdbcProcessClaimStrategy implements ProcessClaimStrategy {

  private final JdbcTemplate jdbc;
  private final JdbcProcessDialect dialect;
  private final WorkerId workerId;

  public JdbcProcessClaimStrategy(
      JdbcTemplate jdbc, JdbcProcessDialect dialect, WorkerId workerId) {
    this.jdbc = jdbc;
    this.dialect = dialect;
    this.workerId = workerId;
  }

  @Override
  public String id() {
    return dialect.id();
  }

  @Override
  public List<String> claimDueEffects(
      Instant now, int limit, String leaseToken, Instant leaseUntil) {
    return dialect.claimDueEffects(jdbc, now, limit, workerId, leaseToken, leaseUntil);
  }

  @Override
  public List<String> claimDueDeadlines(
      Instant now, int limit, String leaseToken, Instant leaseUntil) {
    return dialect.claimDueDeadlines(jdbc, now, limit, workerId, leaseToken, leaseUntil);
  }
}

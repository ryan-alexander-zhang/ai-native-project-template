package com.aipersimmon.ddd.web.store.jdbc;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-backed {@link ReplayGuard}: a nonce is recorded in {@code aipersimmon_web_nonce} on
 * first sight and reported as seen on reuse. The primary key provides the single-use guarantee
 * across instances; an expired entry for the nonce is purged first so the window can roll over.
 */
public class JdbcReplayGuard implements ReplayGuard {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcReplayGuard(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public boolean seenBefore(String nonce, Duration ttl) {
    Instant now = clock.instant();
    String tenant = tenant();
    jdbc.update(
        "DELETE FROM aipersimmon_web_nonce WHERE tenant_id = ? AND nonce = ? AND expires_at <= ?",
        tenant,
        nonce,
        Timestamp.from(now));
    try {
      jdbc.update(
          "INSERT INTO aipersimmon_web_nonce (tenant_id, nonce, created_at, expires_at) VALUES (?, ?, ?, ?)",
          tenant,
          nonce,
          Timestamp.from(now),
          Timestamp.from(now.plus(ttl)));
      return false;
    } catch (DuplicateKeyException e) {
      return true;
    }
  }

  /**
   * The tenant that scopes this nonce, read from the ambient {@link TenantContext} bound on the
   * request edge; the root sentinel when tenancy is off. The nonce is client-supplied, so tenant is
   * part of its identity — see the composite primary key.
   */
  private static String tenant() {
    return TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value());
  }
}

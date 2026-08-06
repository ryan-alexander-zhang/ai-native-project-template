package com.aipersimmon.ddd.web.store.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The sweep's predicate, which is the whole of it.
 *
 * <p>The assertions that carry weight are the ones about rows that must survive: a sweep that is
 * too eager takes a live idempotency claim (turning a retry into a second execution — the exact
 * thing the key exists to prevent) or an unspent nonce (letting a captured request be replayed).
 * One that is too shy merely leaves rows behind, which is the state this job exists to end.
 */
class MybatisPlusWebStoreCleanupTest {

  private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

  private JdbcTemplate jdbc;
  private MybatisPlusWebStoreCleanup cleanup;

  @BeforeEach
  void setUp() {
    String base = "classpath:aipersimmon/db/migration/web-store/h2/";
    DataSource dataSource =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScript(base + "V1__aipersimmon_web_store.sql")
            .addScript(base + "V2__add_tenant_id.sql")
            .addScript(base + "V3__idempotency_claim.sql")
            .addScript(base + "V4__rate_limit_window_index.sql")
            .build();
    jdbc = new JdbcTemplate(dataSource);
    SqlSessionTemplate session = WebStoreMappers.session(dataSource);
    cleanup =
        new MybatisPlusWebStoreCleanup(
            session.getMapper(IdempotencyMapper.class),
            session.getMapper(NonceMapper.class),
            session.getMapper(RateLimitMapper.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofHours(24));
  }

  @Test
  void anExpiredIdempotencyRowIsRemovedEvenThoughItsKeyIsNeverPresentedAgain() {
    idempotency("spent", NOW.minusSeconds(60));

    cleanup.sweep();

    assertEquals(0, count("aipersimmon_web_idempotency"));
  }

  /**
   * A PENDING row's {@code expires_at} is its claim lease — an attempt is running behind it right
   * now. Deleting it frees the key mid-request, and the retry that follows executes a second time.
   */
  @Test
  void aClaimStillWithinItsLeaseIsKept() {
    idempotency("in-flight", NOW.plusSeconds(30));

    cleanup.sweep();

    assertEquals(1, count("aipersimmon_web_idempotency"));
  }

  @Test
  void anUnspentNonceIsKeptAndAnExpiredOneIsNot() {
    nonce("still-valid", NOW.plusSeconds(30));
    nonce("long-gone", NOW.minusSeconds(30));

    cleanup.sweep();

    assertEquals(1, count("aipersimmon_web_nonce"));
    assertEquals(
        "still-valid",
        jdbc.queryForObject("SELECT nonce FROM aipersimmon_web_nonce", String.class));
  }

  /** Exactly at the boundary the row's life is over — {@code expires_at <= now}. */
  @Test
  void aRowExpiringExactlyNowIsRemoved() {
    nonce("on-the-line", NOW);

    cleanup.sweep();

    assertEquals(0, count("aipersimmon_web_nonce"));
  }

  @Test
  void rateLimitWindowsOlderThanTheRetentionGoAndRecentOnesStay() {
    rateLimit("cold-bucket", NOW.minus(Duration.ofHours(25)));
    rateLimit("warm-bucket", NOW.minus(Duration.ofHours(23)));

    cleanup.sweep();

    assertEquals(1, count("aipersimmon_web_rate_limit"));
    assertEquals(
        "warm-bucket",
        jdbc.queryForObject("SELECT bucket_key FROM aipersimmon_web_rate_limit", String.class));
  }

  /** Nothing to do is not an error, and must not be reported as work either. */
  @Test
  void anEmptySweepIsHarmless() {
    cleanup.sweep();

    assertEquals(0, count("aipersimmon_web_nonce"));
  }

  private void idempotency(String key, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO aipersimmon_web_idempotency (tenant_id, principal, idempotency_key,"
            + " fingerprint, state, created_at, expires_at) VALUES ('acme', 'alice', ?, 'fp',"
            + " 'PENDING', ?, ?)",
        key,
        Timestamp.from(NOW.minusSeconds(600)),
        Timestamp.from(expiresAt));
  }

  private void nonce(String value, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO aipersimmon_web_nonce (tenant_id, nonce, created_at, expires_at)"
            + " VALUES ('acme', ?, ?, ?)",
        value,
        Timestamp.from(NOW.minusSeconds(600)),
        Timestamp.from(expiresAt));
  }

  private void rateLimit(String bucket, Instant windowStart) {
    jdbc.update(
        "INSERT INTO aipersimmon_web_rate_limit (tenant_id, bucket_key, window_start, count)"
            + " VALUES ('acme', ?, ?, 1)",
        bucket,
        Timestamp.from(windowStart));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}

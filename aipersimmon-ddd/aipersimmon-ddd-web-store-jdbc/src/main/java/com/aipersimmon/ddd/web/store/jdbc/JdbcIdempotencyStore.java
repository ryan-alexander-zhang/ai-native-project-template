package com.aipersimmon.ddd.web.store.jdbc;

import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-backed {@link IdempotencyStore} over {@code aipersimmon_web_idempotency}.
 *
 * <p>The primary key {@code (tenant_id, principal, idempotency_key)} is the serialisation point: a
 * claim is an {@code INSERT} of a {@code PENDING} row, so exactly one instance can win it however
 * many race, with no lock table and no advisory lock. Losing that insert is not a failure — it is
 * the answer, and the row that beat us says which of the three other states applies.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

  private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

  private static final String STATE_PENDING = "PENDING";
  private static final String STATE_COMPLETE = "COMPLETE";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public JdbcIdempotencyStore(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  private record Row(String state, String fingerprint, StoredResponse response) {}

  @Override
  public IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl) {
    Instant now = clock.instant();
    // Clear an entry that has outlived its deadline before trying to claim. For a COMPLETE row that
    // deadline ends the retry window; for a PENDING one it is the claim lease, so an attempt that
    // died
    // mid-request releases the key instead of holding it until retention expires.
    jdbc.update(
        "DELETE FROM aipersimmon_web_idempotency"
            + " WHERE tenant_id = ? AND principal = ? AND idempotency_key = ? AND expires_at <= ?",
        key.tenant(),
        key.principal(),
        key.key(),
        Timestamp.from(now));
    try {
      jdbc.update(
          "INSERT INTO aipersimmon_web_idempotency"
              + " (tenant_id, principal, idempotency_key, fingerprint, state,"
              + " response_status, response_body, response_headers, created_at, expires_at)"
              + " VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)",
          key.tenant(),
          key.principal(),
          key.key(),
          key.fingerprint(),
          STATE_PENDING,
          Timestamp.from(now),
          Timestamp.from(now.plus(leaseTtl)));
      return new IdempotencyClaim.Won();
    } catch (DuplicateKeyException lost) {
      return describe(key, now);
    }
  }

  @Override
  public void complete(IdempotencyKey key, StoredResponse response, Duration ttl) {
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE aipersimmon_web_idempotency"
            + " SET state = ?, response_status = ?, response_body = ?, response_headers = ?,"
            + " expires_at = ?"
            + " WHERE tenant_id = ? AND principal = ? AND idempotency_key = ?",
        STATE_COMPLETE,
        response.status(),
        response.body(),
        writeHeaders(response.headers()),
        Timestamp.from(now.plus(ttl)),
        key.tenant(),
        key.principal(),
        key.key());
  }

  @Override
  public void abandon(IdempotencyKey key) {
    // Only a claim can be released. A COMPLETE row is an outcome someone is entitled to replay, and
    // the state predicate keeps a late abandon from deleting it.
    jdbc.update(
        "DELETE FROM aipersimmon_web_idempotency"
            + " WHERE tenant_id = ? AND principal = ? AND idempotency_key = ? AND state = ?",
        key.tenant(),
        key.principal(),
        key.key(),
        STATE_PENDING);
  }

  /**
   * What the row that won the race says. It can be gone again by the time we look (another
   * attempt's expiry sweep, or an abandon), which leaves nothing to report — treated as in
   * progress, so the caller retries rather than executing on a guess.
   */
  private IdempotencyClaim describe(IdempotencyKey key, Instant now) {
    Optional<Row> row = read(key, now);
    if (row.isEmpty()) {
      return new IdempotencyClaim.InProgress();
    }
    Row found = row.get();
    if (!found.fingerprint().equals(key.fingerprint())) {
      return new IdempotencyClaim.Mismatch();
    }
    if (STATE_COMPLETE.equals(found.state()) && found.response() != null) {
      return new IdempotencyClaim.Replay(found.response());
    }
    return new IdempotencyClaim.InProgress();
  }

  private Optional<Row> read(IdempotencyKey key, Instant now) {
    List<Row> found =
        jdbc.query(
            "SELECT state, fingerprint, response_status, response_body, response_headers"
                + " FROM aipersimmon_web_idempotency"
                + " WHERE tenant_id = ? AND principal = ? AND idempotency_key = ?"
                + " AND expires_at > ?",
            (rs, rowNum) -> {
              int status = rs.getInt("response_status");
              StoredResponse response =
                  rs.wasNull()
                      ? null
                      : new StoredResponse(
                          status,
                          rs.getBytes("response_body"),
                          readHeaders(rs.getString("response_headers")));
              return new Row(
                  rs.getString("state"), nullToEmpty(rs.getString("fingerprint")), response);
            },
            key.tenant(),
            key.principal(),
            key.key(),
            Timestamp.from(now));
    return found.stream().findFirst();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private Map<String, String> readHeaders(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, HEADERS_TYPE);
    } catch (Exception e) {
      // A response whose headers cannot be read back is not worth replaying: Location is what a 201
      // means, so answering without it is worse than admitting the entry is unusable. The Redis
      // backend fails the same way for the same reason — the two must not disagree here.
      throw new IllegalStateException(
          "stored idempotent response has unreadable headers; entry is unusable", e);
    }
  }

  private String writeHeaders(Map<String, String> headers) {
    try {
      return objectMapper.writeValueAsString(headers);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialise idempotent response headers", e);
    }
  }
}

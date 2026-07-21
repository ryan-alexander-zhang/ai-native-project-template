package com.aipersimmon.ddd.web.store.jdbc;

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
 * JdbcTemplate-backed {@link IdempotencyStore}: the first response for a key is inserted into
 * {@code aipersimmon_web_idempotency} and replayed on retry. {@link #saveIfAbsent} relies on the
 * primary key for atomicity — a duplicate insert loses — after purging any expired entry for the
 * key.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

  private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public JdbcIdempotencyStore(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public Optional<StoredResponse> find(String key) {
    List<StoredResponse> found =
        jdbc.query(
            "SELECT response_status, response_body, response_headers FROM aipersimmon_web_idempotency "
                + "WHERE idempotency_key = ? AND expires_at > ?",
            (rs, rowNum) ->
                new StoredResponse(
                    rs.getInt("response_status"),
                    rs.getBytes("response_body"),
                    readHeaders(rs.getString("response_headers"))),
            key,
            Timestamp.from(clock.instant()));
    return found.stream().findFirst();
  }

  @Override
  public boolean saveIfAbsent(String key, StoredResponse response, Duration ttl) {
    Instant now = clock.instant();
    jdbc.update(
        "DELETE FROM aipersimmon_web_idempotency WHERE idempotency_key = ? AND expires_at <= ?",
        key,
        Timestamp.from(now));
    try {
      jdbc.update(
          "INSERT INTO aipersimmon_web_idempotency "
              + "(idempotency_key, response_status, response_body, response_headers, created_at, expires_at) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          key,
          response.status(),
          response.body(),
          writeHeaders(response.headers()),
          Timestamp.from(now),
          Timestamp.from(now.plus(ttl)));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  private Map<String, String> readHeaders(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, HEADERS_TYPE);
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String writeHeaders(Map<String, String> headers) {
    try {
      return objectMapper.writeValueAsString(headers);
    } catch (Exception e) {
      return "{}";
    }
  }
}

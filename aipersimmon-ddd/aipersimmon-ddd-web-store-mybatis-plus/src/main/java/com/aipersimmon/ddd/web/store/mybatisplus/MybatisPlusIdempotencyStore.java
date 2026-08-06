package com.aipersimmon.ddd.web.store.mybatisplus;

import com.aipersimmon.ddd.web.spi.IdempotencyClaim;
import com.aipersimmon.ddd.web.spi.IdempotencyKey;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis-Plus-backed {@link IdempotencyStore} over {@code aipersimmon_web_idempotency}.
 *
 * <p>The primary key {@code (tenant_id, principal, idempotency_key)} is the serialisation point: a
 * claim is an {@code INSERT} of a {@code PENDING} row, so exactly one instance can win it however
 * many race, with no lock table and no advisory lock. Losing that insert is not a failure — it is
 * the answer, and the row that beat us says which of the three other states applies.
 */
public class MybatisPlusIdempotencyStore implements IdempotencyStore {

  private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

  private static final String STATE_PENDING = "PENDING";
  private static final String STATE_COMPLETE = "COMPLETE";

  private final IdempotencyMapper mapper;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public MybatisPlusIdempotencyStore(
      IdempotencyMapper mapper, ObjectMapper objectMapper, Clock clock) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl) {
    Instant now = clock.instant();
    // Clear an entry that has outlived its deadline before trying to claim. For a COMPLETE row that
    // deadline ends the retry window; for a PENDING one it is the claim lease, so an attempt that
    // died mid-request releases the key instead of holding it until retention expires.
    mapper.delete(identity(key).le(IdempotencyRecord::getExpiresAt, now));
    try {
      mapper.insert(
          new IdempotencyRecord(
              key.tenant(),
              key.principal(),
              key.key(),
              key.fingerprint(),
              STATE_PENDING,
              now,
              now.plus(leaseTtl)));
      return new IdempotencyClaim.Won();
    } catch (DuplicateKeyException lost) {
      return describe(key, now);
    }
  }

  @Override
  public void complete(IdempotencyKey key, StoredResponse response, Duration ttl) {
    Instant now = clock.instant();
    mapper.complete(
        key.tenant(),
        key.principal(),
        key.key(),
        STATE_COMPLETE,
        response.status(),
        response.body(),
        writeHeaders(response.headers()),
        now.plus(ttl));
  }

  @Override
  public void abandon(IdempotencyKey key) {
    // Only a claim can be released. A COMPLETE row is an outcome someone is entitled to replay, and
    // the state predicate keeps a late abandon from deleting it.
    mapper.delete(identity(key).eq(IdempotencyRecord::getState, STATE_PENDING));
  }

  /**
   * What the row that won the race says. It can be gone again by the time we look (another
   * attempt's expiry sweep, or an abandon), which leaves nothing to report — treated as in
   * progress, so the caller retries rather than executing on a guess.
   */
  private IdempotencyClaim describe(IdempotencyKey key, Instant now) {
    Optional<IdempotencyRecord> row = read(key, now);
    if (row.isEmpty()) {
      return new IdempotencyClaim.InProgress();
    }
    IdempotencyRecord found = row.get();
    if (!nullToEmpty(found.getFingerprint()).equals(key.fingerprint())) {
      return new IdempotencyClaim.Mismatch();
    }
    if (STATE_COMPLETE.equals(found.getState()) && found.getResponseStatus() != null) {
      return new IdempotencyClaim.Replay(
          new StoredResponse(
              found.getResponseStatus(),
              found.getResponseBody(),
              readHeaders(found.getResponseHeaders())));
    }
    return new IdempotencyClaim.InProgress();
  }

  private Optional<IdempotencyRecord> read(IdempotencyKey key, Instant now) {
    List<IdempotencyRecord> found =
        mapper.selectList(
            identity(key)
                .select(
                    IdempotencyRecord::getState,
                    IdempotencyRecord::getFingerprint,
                    IdempotencyRecord::getResponseStatus,
                    IdempotencyRecord::getResponseBody,
                    IdempotencyRecord::getResponseHeaders)
                .gt(IdempotencyRecord::getExpiresAt, now));
    return found.stream().findFirst();
  }

  /**
   * The full composite key, always all three columns. Never partially qualified: the
   * client-supplied key alone addresses one row per tenant and per caller, so a predicate missing
   * either column would read or delete somebody else's entry.
   */
  private static LambdaQueryWrapper<IdempotencyRecord> identity(IdempotencyKey key) {
    return new LambdaQueryWrapper<IdempotencyRecord>()
        .eq(IdempotencyRecord::getTenantId, key.tenant())
        .eq(IdempotencyRecord::getPrincipal, key.principal())
        .eq(IdempotencyRecord::getIdempotencyKey, key.key());
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

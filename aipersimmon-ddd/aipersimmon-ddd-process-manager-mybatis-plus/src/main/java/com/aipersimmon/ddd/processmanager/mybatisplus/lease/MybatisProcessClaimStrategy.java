package com.aipersimmon.ddd.processmanager.mybatisplus.lease;

import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The MyBatis-Plus implementation of {@link ProcessClaimStrategy}. It reproduces the JDBC dialects:
 * a {@code SKIP LOCKED} claim (locks the due candidates, skipping contended rows, then marks them
 * {@code IN_FLIGHT}) on PostgreSQL/MySQL, and an atomic conditional-{@code UPDATE} claim (races
 * each due candidate into {@code IN_FLIGHT}, keeping only the winners) where {@code SKIP LOCKED} is
 * unavailable (H2). The claim runs inside the transaction opened by the engine's unit of work.
 */
public final class MybatisProcessClaimStrategy implements ProcessClaimStrategy {

  private final ProcessClaimMapper mapper;
  private final String id;
  private final boolean skipLocked;
  private final WorkerId workerId;

  public MybatisProcessClaimStrategy(
      ProcessClaimMapper mapper, String id, boolean skipLocked, WorkerId workerId) {
    this.mapper = mapper;
    this.id = id;
    this.skipLocked = skipLocked;
    this.workerId = workerId;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public List<String> claimDueEffects(
      Instant now, int limit, String leaseToken, Instant leaseUntil) {
    Timestamp ts = Timestamp.from(now);
    Timestamp until = Timestamp.from(leaseUntil);
    if (skipLocked) {
      List<String> ids = mapper.candidateEffectsSkipLocked(ts, limit);
      for (String effectId : ids) {
        mapper.markEffectInFlight(effectId, workerId.value(), leaseToken, until, ts);
      }
      return ids;
    }
    List<String> claimed = new ArrayList<>();
    for (String effectId : mapper.candidateEffects(ts, limit)) {
      if (mapper.markEffectInFlightIfDue(effectId, workerId.value(), leaseToken, until, ts) == 1) {
        claimed.add(effectId);
      }
    }
    return claimed;
  }

  @Override
  public List<String> claimDueDeadlines(
      Instant now, int limit, String leaseToken, Instant leaseUntil) {
    Timestamp ts = Timestamp.from(now);
    Timestamp until = Timestamp.from(leaseUntil);
    if (skipLocked) {
      List<String> ids = mapper.candidateDeadlinesSkipLocked(ts, limit);
      for (String deadlineId : ids) {
        mapper.markDeadlineInFlight(deadlineId, workerId.value(), leaseToken, until, ts);
      }
      return ids;
    }
    List<String> claimed = new ArrayList<>();
    for (String deadlineId : mapper.candidateDeadlines(ts, limit)) {
      if (mapper.markDeadlineInFlightIfDue(deadlineId, workerId.value(), leaseToken, until, ts)
          == 1) {
        claimed.add(deadlineId);
      }
    }
    return claimed;
  }
}

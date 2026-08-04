package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ExportClaims;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claim, in the two statements the library's own relays use.
 *
 * <p>{@code REQUIRES_NEW} rather than {@code REQUIRED}: the claim must be committed before the export starts, or
 * every other worker would keep seeing the job as claimable for the whole length of the run — the row locks the
 * candidate select takes are released at commit, and the status change is not visible until then either. Nesting
 * it inside a caller's longer transaction would therefore turn the claim into a lock held for hours.
 *
 * <p>The loop over candidates matters at more than one worker. With a batch of one, two workers polling at the
 * same instant would have one of them take the row and the other come back empty even though the queue had ten
 * jobs. Asking for a handful of candidates and taking the first that can still be claimed makes a fleet drain a
 * backlog instead of taking turns.
 */
@Component
class MyBatisExportClaims implements ExportClaims {

  /** How many candidates one claim attempt looks at before giving up. */
  private static final int CANDIDATES = 8;

  private final ExportJobMapper mapper;

  MyBatisExportClaims(ExportJobMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<ExportJobId> claimNext(String owner, Duration lease, Instant now) {
    Timestamp at = Timestamp.from(now);
    Timestamp until = Timestamp.from(now.plus(lease));
    List<String> candidates = mapper.claimCandidates(at, CANDIDATES);
    for (String id : candidates) {
      if (mapper.claim(id, owner, until, at) == 1) {
        return Optional.of(new ExportJobId(id));
      }
    }
    return Optional.empty();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean heartbeat(ExportJobId id, String owner, Instant leaseUntil) {
    return mapper.heartbeat(id.value(), owner, Timestamp.from(leaseUntil)) == 1;
  }
}

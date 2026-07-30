package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ProcessDeadlineStore} over a map.
 *
 * <p>Two things are kept exactly as the SQL has them, because the worker's correctness is built on
 * them. <strong>Generations</strong>: rescheduling a named timer mints a new generation, and an
 * older generation firing is a stale timer that must become an auditable no-op rather than an event
 * — the classic bug where cancelling a timeout and setting a new one leaves the old one to fire
 * anyway. <strong>Lease fencing</strong>: every completing transition returns 0 unless the caller
 * still holds the row's lease.
 */
public final class InMemoryProcessDeadlineStore implements ProcessDeadlineStore, Snapshottable {

  /** One row of {@code aipersimmon_process_deadline}. */
  public static final class Row {
    private final ProcessDeadlineInsert inserted;
    private final Instant createdAt;
    private DeadlineStatus status = DeadlineStatus.PENDING;
    private int attempts;
    private Instant dueAt;
    private String leaseToken;
    private Instant leaseUntil;
    private String lastError;

    Row(ProcessDeadlineInsert inserted, Instant createdAt) {
      this.inserted = inserted;
      this.createdAt = createdAt;
      this.dueAt = inserted.dueAt();
    }

    public DeadlineStatus status() {
      return status;
    }

    public int attempts() {
      return attempts;
    }

    public long generation() {
      return inserted.generation();
    }

    public String lastError() {
      return lastError;
    }
  }

  private final Map<String, Row> rows = new LinkedHashMap<>();
  private final Map<String, Long> generations = new HashMap<>();

  private static String nameKey(ProcessInstanceId instanceId, DeadlineName name) {
    return instanceId.value() + "/" + name.value();
  }

  @Override
  public long nextGeneration(ProcessInstanceId instanceId, DeadlineName name) {
    return generations.merge(nameKey(instanceId, name), 1L, Long::sum);
  }

  @Override
  public long currentGeneration(ProcessInstanceId instanceId, DeadlineName name) {
    return generations.getOrDefault(nameKey(instanceId, name), 0L);
  }

  @Override
  public void schedule(ProcessDeadlineInsert deadline, Instant now) {
    rows.put(deadline.deadlineId(), new Row(deadline, now));
    generations.merge(
        nameKey(deadline.instanceId(), deadline.name()), deadline.generation(), Math::max);
  }

  /** Due, PENDING or with an expired IN_FLIGHT lease, oldest first. */
  public List<String> claimDue(Instant now, int limit, String leaseToken, Instant leaseUntil) {
    List<Row> claimable =
        rows.values().stream()
            .filter(row -> row.dueAt == null || !row.dueAt.isAfter(now))
            .filter(
                row ->
                    row.status == DeadlineStatus.PENDING
                        || (row.status == DeadlineStatus.IN_FLIGHT
                            && (row.leaseUntil == null || !row.leaseUntil.isAfter(now))))
            .sorted(Comparator.comparing(row -> row.dueAt))
            .limit(limit)
            .toList();
    List<String> claimed = new ArrayList<>(claimable.size());
    for (Row row : claimable) {
      row.status = DeadlineStatus.IN_FLIGHT;
      row.leaseToken = leaseToken;
      row.leaseUntil = leaseUntil;
      claimed.add(row.inserted.deadlineId());
    }
    return claimed;
  }

  @Override
  public void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now) {
    long current = currentGeneration(instanceId, name);
    rows.values().stream()
        .filter(row -> row.inserted.instanceId().equals(instanceId))
        .filter(row -> row.inserted.name().equals(name))
        .filter(row -> row.inserted.generation() == current)
        .filter(row -> row.status == DeadlineStatus.PENDING)
        .forEach(row -> row.status = DeadlineStatus.CANCELLED);
  }

  @Override
  public int cancelLive(ProcessInstanceId instanceId, Instant now) {
    int cancelled = 0;
    for (Row row : rows.values()) {
      if (row.inserted.instanceId().equals(instanceId)
          && (row.status == DeadlineStatus.PENDING || row.status == DeadlineStatus.IN_FLIGHT)) {
        row.status = DeadlineStatus.CANCELLED;
        cancelled++;
      }
    }
    return cancelled;
  }

  @Override
  public int cancelClaimed(String deadlineId, String leaseToken, Instant now) {
    return underLease(deadlineId, leaseToken, row -> row.status = DeadlineStatus.CANCELLED);
  }

  @Override
  public Optional<DeadlineStatus> statusForUpdate(String deadlineId) {
    return Optional.ofNullable(rows.get(deadlineId)).map(row -> row.status);
  }

  @Override
  public Optional<DeadlineRow> load(String deadlineId) {
    return Optional.ofNullable(rows.get(deadlineId))
        .map(
            row ->
                new DeadlineRow(
                    row.inserted.tenantId(),
                    row.inserted.deadlineId(),
                    row.inserted.instanceId(),
                    row.inserted.name(),
                    row.inserted.generation(),
                    new PayloadType(row.inserted.inputType(), row.inserted.inputVersion()),
                    row.inserted.inputPayload(),
                    row.inserted.correlationId(),
                    row.inserted.causationId(),
                    row.attempts,
                    row.inserted.traceparent(),
                    row.inserted.traceState()));
  }

  @Override
  public int markFired(String deadlineId, String leaseToken, Instant now) {
    return underLease(
        deadlineId,
        leaseToken,
        row -> {
          row.status = DeadlineStatus.FIRED;
          row.leaseToken = null;
        });
  }

  @Override
  public int scheduleRetry(
      String deadlineId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
    return underLease(
        deadlineId,
        leaseToken,
        row -> {
          row.status = DeadlineStatus.PENDING;
          row.attempts++;
          row.dueAt = nextAttemptAt;
          row.lastError = error;
          row.leaseToken = null;
        });
  }

  @Override
  public int markDead(String deadlineId, String leaseToken, String error, Instant now) {
    return underLease(
        deadlineId,
        leaseToken,
        row -> {
          row.status = DeadlineStatus.DEAD;
          row.attempts++;
          row.lastError = error;
          row.leaseToken = null;
        });
  }

  @Override
  public int redrive(String deadlineId, Instant now) {
    Row row = rows.get(deadlineId);
    if (row == null || row.status != DeadlineStatus.DEAD) {
      return 0;
    }
    row.status = DeadlineStatus.PENDING;
    row.attempts = 0;
    row.dueAt = now;
    return 1;
  }

  @Override
  public long countDead() {
    return rows.values().stream().filter(row -> row.status == DeadlineStatus.DEAD).count();
  }

  @Override
  public long countDead(ProcessInstanceId instanceId) {
    return rows.values().stream()
        .filter(row -> row.status == DeadlineStatus.DEAD)
        .filter(row -> row.inserted.instanceId().equals(instanceId))
        .count();
  }

  @Override
  public Optional<Instant> oldestDuePending(Instant now) {
    return rows.values().stream()
        .filter(row -> row.status == DeadlineStatus.PENDING)
        .map(row -> row.dueAt)
        .filter(due -> due != null && !due.isAfter(now))
        .min(Comparator.naturalOrder());
  }

  @Override
  public List<ProcessDeadlineView> byStatus(DeadlineStatus status, int limit) {
    return rows.values().stream()
        .filter(row -> row.status == status)
        .limit(limit)
        .map(
            row ->
                new ProcessDeadlineView(
                    row.inserted.deadlineId(),
                    row.inserted.instanceId().value(),
                    row.inserted.name().value(),
                    row.inserted.generation(),
                    row.status.name(),
                    row.dueAt,
                    row.attempts,
                    Optional.ofNullable(row.dueAt),
                    Optional.ofNullable(row.lastError)))
        .toList();
  }

  private int underLease(
      String deadlineId, String leaseToken, java.util.function.Consumer<Row> change) {
    Row row = rows.get(deadlineId);
    if (row == null || row.leaseToken == null || !row.leaseToken.equals(leaseToken)) {
      return 0;
    }
    change.accept(row);
    return 1;
  }

  /** Every mutable field of every row, plus the generation counters. */
  private record Saved(Map<String, Object[]> rows, Map<String, Long> generations) {}

  @Override
  public Object snapshot() {
    Map<String, Object[]> saved = new LinkedHashMap<>();
    rows.forEach(
        (id, row) ->
            saved.put(
                id,
                new Object[] {
                  row.status, row.attempts, row.dueAt, row.leaseToken, row.leaseUntil, row.lastError
                }));
    return new Saved(saved, new HashMap<>(generations));
  }

  @Override
  public void restore(Object snapshot) {
    Saved saved = (Saved) snapshot;
    rows.keySet().removeIf(id -> !saved.rows().containsKey(id));
    saved
        .rows()
        .forEach(
            (id, fields) -> {
              Row row = rows.get(id);
              if (row == null) {
                return;
              }
              row.status = (DeadlineStatus) fields[0];
              row.attempts = (Integer) fields[1];
              row.dueAt = (Instant) fields[2];
              row.leaseToken = (String) fields[3];
              row.leaseUntil = (Instant) fields[4];
              row.lastError = (String) fields[5];
            });
    generations.clear();
    generations.putAll(saved.generations());
  }

  // --- what a test needs ------------------------------------------------------------------------

  public Row row(String deadlineId) {
    Row row = rows.get(deadlineId);
    if (row == null) {
      throw new IllegalArgumentException("no deadline " + deadlineId + "; holds " + rows.keySet());
    }
    return row;
  }

  public void expireLease(String deadlineId) {
    row(deadlineId).leaseUntil = Instant.EPOCH;
  }

  /** Arms a timer the way a scheduling advance does, taking the next generation for its name. */
  public ProcessDeadlineInsert arm(
      String deadlineId, ProcessInstanceId instanceId, DeadlineName name, Instant dueAt) {
    return new ProcessDeadlineInsert(
        "acme",
        deadlineId,
        instanceId,
        name,
        nextGeneration(instanceId, name),
        dueAt,
        "sample.payload",
        1,
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "corr-1",
        "cause-1",
        null,
        null);
  }
}

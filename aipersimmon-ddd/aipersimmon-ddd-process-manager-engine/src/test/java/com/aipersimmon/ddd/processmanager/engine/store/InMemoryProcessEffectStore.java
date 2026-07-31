package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ProcessEffectStore} over a map, so the engine's delivery reasoning can be tested without
 * a database.
 *
 * <p>The two things it does <em>not</em> simplify are the two the engine's guarantees rest on.
 * Every completing transition is <strong>fenced by the lease token</strong> and returns 0 when the
 * token does not match, exactly as the SQL {@code WHERE lease_token = ?} does — that fence is what
 * stops a worker whose lease expired mid-delivery from marking an effect the new owner is already
 * redelivering. And {@code attempts} is incremented by {@code scheduleRetry}/{@code markDead},
 * never by claiming, so a slow worker's reclaim cannot spend the retry budget.
 *
 * <p>Whether a given SQL dialect implements those correctly stays with the backends and their own
 * tests against a real database. This exists so the decisions <em>above</em> the port can be tested
 * for what they are.
 */
public final class InMemoryProcessEffectStore implements ProcessEffectStore, Snapshottable {

  /** One row of {@code aipersimmon_process_effect}. */
  public static final class Row {
    private final ProcessEffectInsert inserted;
    private final Instant createdAt;
    private EffectStatus status = EffectStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private String leaseToken;
    private Instant leaseUntil;
    private String lastError;

    Row(ProcessEffectInsert inserted, Instant createdAt) {
      this.inserted = inserted;
      this.createdAt = createdAt;
      this.nextAttemptAt = createdAt;
    }

    public EffectStatus status() {
      return status;
    }

    public int attempts() {
      return attempts;
    }

    public Instant nextAttemptAt() {
      return nextAttemptAt;
    }

    public String lastError() {
      return lastError;
    }

    public String leaseToken() {
      return leaseToken;
    }
  }

  private final Map<String, Row> rows = new LinkedHashMap<>();
  private final Map<String, Long> seqByInstance = new LinkedHashMap<>();

  @Override
  public long nextSeq(ProcessInstanceId instanceId) {
    return seqByInstance.merge(instanceId.value(), 1L, Long::sum);
  }

  @Override
  public void insert(ProcessEffectInsert effect, Instant now) {
    rows.put(effect.effectId(), new Row(effect, now));
  }

  /**
   * The claim, spelled out: due, {@code PENDING} or with an expired {@code IN_FLIGHT} lease, and
   * ordered by {@code (instance, seq)} so one instance's effects go out in the order they were
   * staged. Claiming stamps a fresh lease and marks the row {@code IN_FLIGHT} — it does not touch
   * {@code attempts}.
   */
  public List<String> claimDue(Instant now, int limit, String leaseToken, Instant leaseUntil) {
    List<Row> claimable =
        rows.values().stream()
            .filter(row -> isClaimable(row, now))
            .sorted(
                Comparator.comparing((Row row) -> row.inserted.instanceId().value())
                    .thenComparingLong(row -> row.inserted.seq()))
            .limit(limit)
            .toList();
    List<String> claimed = new ArrayList<>(claimable.size());
    for (Row row : claimable) {
      row.status = EffectStatus.IN_FLIGHT;
      row.leaseToken = leaseToken;
      row.leaseUntil = leaseUntil;
      claimed.add(row.inserted.effectId());
    }
    return claimed;
  }

  private static boolean isClaimable(Row row, Instant now) {
    boolean due = row.nextAttemptAt == null || !row.nextAttemptAt.isAfter(now);
    if (!due) {
      return false;
    }
    if (row.status == EffectStatus.PENDING) {
      return true;
    }
    // A crashed worker releases nothing; its lease simply runs out where it stands, and the row is
    // re-claimed and re-delivered under the same id.
    return row.status == EffectStatus.IN_FLIGHT
        && (row.leaseUntil == null || !row.leaseUntil.isAfter(now));
  }

  @Override
  public Optional<ClaimedEffect> load(String effectId) {
    return Optional.ofNullable(rows.get(effectId))
        .map(
            row ->
                new ClaimedEffect(
                    row.inserted.effectId(),
                    row.inserted.instanceId(),
                    row.inserted.kind(),
                    new PayloadType(row.inserted.payloadType(), row.inserted.payloadVersion()),
                    row.inserted.payload(),
                    new CommandContext(
                        Tenants.fromValue(row.inserted.tenantId()),
                        row.inserted.messageId(),
                        row.inserted.correlationId(),
                        row.inserted.causationId()),
                    row.attempts,
                    row.inserted.traceparent(),
                    row.inserted.traceState()));
  }

  @Override
  public int markDelivered(String effectId, String leaseToken, Instant now) {
    return underLease(
        effectId,
        leaseToken,
        row -> {
          row.status = EffectStatus.DELIVERED;
          row.leaseToken = null;
          row.leaseUntil = null;
        });
  }

  @Override
  public int scheduleRetry(
      String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
    return underLease(
        effectId,
        leaseToken,
        row -> {
          row.status = EffectStatus.PENDING;
          row.attempts++;
          row.nextAttemptAt = nextAttemptAt;
          row.lastError = error;
          row.leaseToken = null;
          row.leaseUntil = null;
        });
  }

  @Override
  public int markDead(String effectId, String leaseToken, String error, Instant now) {
    return underLease(
        effectId,
        leaseToken,
        row -> {
          row.status = EffectStatus.DEAD;
          row.attempts++;
          row.lastError = error;
          row.leaseToken = null;
          row.leaseUntil = null;
        });
  }

  @Override
  public int markCancelled(String effectId, String leaseToken, Instant now) {
    return underLease(
        effectId,
        leaseToken,
        row -> {
          row.status = EffectStatus.CANCELLED;
          row.leaseToken = null;
          row.leaseUntil = null;
        });
  }

  @Override
  public int redrive(String effectId, Instant now) {
    Row row = rows.get(effectId);
    if (row == null || row.status != EffectStatus.DEAD) {
      return 0;
    }
    row.status = EffectStatus.PENDING;
    row.attempts = 0;
    row.nextAttemptAt = now;
    return 1;
  }

  @Override
  public int cancelPending(ProcessInstanceId instanceId, Instant now) {
    int cancelled = 0;
    for (Row row : rows.values()) {
      if (row.inserted.instanceId().equals(instanceId) && row.status == EffectStatus.PENDING) {
        row.status = EffectStatus.CANCELLED;
        cancelled++;
      }
    }
    return cancelled;
  }

  @Override
  public long countDead() {
    return rows.values().stream().filter(row -> row.status == EffectStatus.DEAD).count();
  }

  @Override
  public long countDead(ProcessInstanceId instanceId) {
    return rows.values().stream()
        .filter(row -> row.status == EffectStatus.DEAD)
        .filter(row -> row.inserted.instanceId().equals(instanceId))
        .count();
  }

  @Override
  public Optional<Instant> oldestDuePending(Instant now) {
    return rows.values().stream()
        .filter(row -> row.status == EffectStatus.PENDING)
        .map(row -> row.nextAttemptAt)
        .filter(due -> due != null && !due.isAfter(now))
        .min(Comparator.naturalOrder());
  }

  @Override
  public List<ProcessEffectView> byStatus(EffectStatus status, int limit) {
    return rows.values().stream()
        .filter(row -> row.status == status)
        .limit(limit)
        .map(
            row ->
                new ProcessEffectView(
                    row.inserted.effectId(),
                    row.inserted.instanceId().value(),
                    row.inserted.kind().name(),
                    row.status.name(),
                    row.attempts,
                    row.inserted.messageId(),
                    Optional.ofNullable(row.nextAttemptAt),
                    Optional.ofNullable(row.lastError),
                    row.createdAt))
        .toList();
  }

  /**
   * Applies a completing transition only when the caller still holds the row's lease, returning the
   * affected row count the way the SQL {@code UPDATE ... WHERE lease_token = ?} does. Every
   * completing transition goes through here, which is the point: a worker that lost its lease must
   * not be able to complete an effect somebody else has taken over.
   */
  private int underLease(
      String effectId, String leaseToken, java.util.function.Consumer<Row> change) {
    Row row = rows.get(effectId);
    if (row == null || row.leaseToken == null || !row.leaseToken.equals(leaseToken)) {
      return 0;
    }
    change.accept(row);
    return 1;
  }

  private record Saved(Map<String, Object[]> rows) {}

  @Override
  public Object snapshot() {
    Map<String, Object[]> saved = new LinkedHashMap<>();
    rows.forEach(
        (id, row) ->
            saved.put(
                id,
                new Object[] {
                  row.status,
                  row.attempts,
                  row.nextAttemptAt,
                  row.leaseToken,
                  row.leaseUntil,
                  row.lastError
                }));
    return new Saved(saved);
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
              row.status = (EffectStatus) fields[0];
              row.attempts = (Integer) fields[1];
              row.nextAttemptAt = (Instant) fields[2];
              row.leaseToken = (String) fields[3];
              row.leaseUntil = (Instant) fields[4];
              row.lastError = (String) fields[5];
            });
  }

  // --- what a test needs to look at ------------------------------------------------------------

  public Row row(String effectId) {
    Row row = rows.get(effectId);
    if (row == null) {
      throw new IllegalArgumentException("no effect " + effectId + "; holds " + rows.keySet());
    }
    return row;
  }

  /** Forces a row's lease to have expired, standing in for a worker that was killed. */
  public void expireLease(String effectId) {
    row(effectId).leaseUntil = Instant.EPOCH;
  }

  public List<String> effectIds() {
    return List.copyOf(rows.keySet());
  }

  /** A staged effect, written the way the advance transaction writes one: PENDING and due now. */
  public ProcessEffectInsert stage(
      String effectId, ProcessInstanceId instanceId, long seq, ProcessEffectKind kind) {
    return new ProcessEffectInsert(
        "acme",
        effectId,
        instanceId,
        "transition-1",
        0,
        seq,
        kind,
        "sample.payload",
        1,
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        effectId,
        "corr-1",
        "cause-1",
        null,
        null);
  }
}

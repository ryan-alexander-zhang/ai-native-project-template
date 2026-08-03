package com.aipersimmon.ddd.outbox.engine.store;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@link OutboxStore} over a map, so the engine's own reasoning can be tested without a database
 * or a Docker daemon.
 *
 * <p>It is not a stub that returns whatever a test asked for. It implements {@link #claimDue} to
 * the letter of the port's contract — including the head-of-aggregate clause and the lease check —
 * because that contract is precisely what the relay's guarantees rest on. A store that handed back
 * rows the real one would not claim would make the tests agree with each other and with nothing
 * else.
 *
 * <p>What it deliberately does <em>not</em> stand in for is the two SQL implementations. Whether
 * {@code JdbcOutboxStore} and {@code MybatisOutboxStore} honour the same contract is a question
 * about SQL, and their own tests answer it against a real database. This one exists so the
 * decisions above the port — retry budgets, what a mark-sent failure means, when a poll stops — can
 * be tested for what they are: pure logic that must not differ between backends.
 */
public final class InMemoryOutboxStore implements OutboxStore {

  /** One stored row. Mutable, like the table it stands for. */
  private static final class Row {
    private final OutboxInsert inserted;
    private final long sequence;
    private boolean sent;
    private Instant sentAt;
    private int attempts;
    private Instant nextAttemptAt;
    private String leaseOwner;
    private String leaseToken;
    private Instant leaseUntil;

    Row(OutboxInsert inserted, long sequence) {
      this.inserted = inserted;
      this.sequence = sequence;
    }

    void clearLease() {
      leaseOwner = null;
      leaseToken = null;
      leaseUntil = null;
    }

    boolean live(int maxAttempts) {
      return !sent && attempts < maxAttempts;
    }

    boolean due(Instant now) {
      return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }

    boolean unleased(Instant now) {
      return leaseUntil == null || !leaseUntil.isAfter(now);
    }
  }

  private final Map<String, Row> rows = new LinkedHashMap<>();
  private long nextSequence;

  /** Set to fail the next call of the named operation, to test what the engine does about it. */
  private String failing;

  /** Makes {@code operation} throw until cleared, so a failure path can be exercised. */
  public void failOn(String operation) {
    this.failing = operation;
  }

  private void failIfAsked(String operation) {
    if (Objects.equals(failing, operation)) {
      throw new IllegalStateException("the store was told to fail " + operation);
    }
  }

  @Override
  public void insert(OutboxInsert row) {
    failIfAsked("insert");
    if (rows.containsKey(row.eventId())) {
      throw new org.springframework.dao.DuplicateKeyException(
          "outbox already holds event " + row.eventId());
    }
    rows.put(row.eventId(), new Row(row, nextSequence++));
  }

  @Override
  public List<PendingMessage> claimDue(
      Instant now, int maxAttempts, int batchSize, OutboxLease lease) {
    failIfAsked("claimDue");
    List<Row> claimable =
        rows.values().stream()
            .filter(row -> row.live(maxAttempts) && row.due(now) && row.unleased(now))
            .filter(row -> isHeadOfItsAggregate(row, maxAttempts))
            .sorted(
                Comparator.comparing((Row row) -> row.inserted.createdAt())
                    .thenComparingLong(row -> row.sequence))
            .limit(batchSize)
            .toList();
    List<PendingMessage> claimed = new ArrayList<>(claimable.size());
    for (Row row : claimable) {
      row.leaseOwner = lease.owner();
      row.leaseToken = lease.token();
      row.leaseUntil = lease.until();
      claimed.add(
          new PendingMessage(
              messageOf(row), row.attempts, row.inserted.traceparent(), row.inserted.traceState()));
    }
    return claimed;
  }

  /**
   * The port's ordering guarantee, spelled out: a row with a subject is claimable only when no
   * earlier row of that subject is still live. Earlier is by {@code (createdAt, sequence)},
   * matching the {@code (created_at, id)} the SQL stores use. A null or blank subject carries no
   * ordering key, so it neither blocks nor is blocked.
   */
  private boolean isHeadOfItsAggregate(Row row, int maxAttempts) {
    String subject = row.inserted.subject();
    if (subject == null || subject.isBlank()) {
      return true;
    }
    return rows.values().stream()
        .filter(other -> subject.equals(other.inserted.subject()))
        .filter(other -> other.live(maxAttempts))
        .noneMatch(other -> isEarlier(other, row));
  }

  private static boolean isEarlier(Row candidate, Row than) {
    int byCreatedAt = candidate.inserted.createdAt().compareTo(than.inserted.createdAt());
    return byCreatedAt < 0 || (byCreatedAt == 0 && candidate.sequence < than.sequence);
  }

  @Override
  public void release(List<String> eventIds) {
    failIfAsked("release");
    eventIds.forEach(eventId -> mutate(eventId, Row::clearLease));
  }

  @Override
  public void markSent(List<String> eventIds, Instant sentAt) {
    failIfAsked("markSent");
    eventIds.forEach(
        eventId ->
            mutate(
                eventId,
                row -> {
                  row.sent = true;
                  row.sentAt = sentAt;
                  row.clearLease();
                }));
  }

  @Override
  public void scheduleRetry(String eventId, Instant nextAttemptAt) {
    failIfAsked("scheduleRetry");
    mutate(
        eventId,
        row -> {
          row.attempts++;
          row.nextAttemptAt = nextAttemptAt;
          row.clearLease();
        });
  }

  @Override
  public void backOffWithoutAttempt(String eventId, Instant nextAttemptAt) {
    failIfAsked("backOffWithoutAttempt");
    mutate(
        eventId,
        row -> {
          row.nextAttemptAt = nextAttemptAt;
          row.clearLease();
        });
  }

  @Override
  public int deleteSentBefore(Instant sentBefore, int limit) {
    failIfAsked("deleteSentBefore");
    // Honours the page bound like a real backend, so the caller's loop-until-short-page logic is
    // actually exercised rather than handed everything in one call.
    List<String> spent =
        rows.values().stream()
            .filter(row -> row.sent && row.sentAt != null && row.sentAt.isBefore(sentBefore))
            .map(row -> row.inserted.eventId())
            .limit(limit)
            .toList();
    spent.forEach(rows::remove);
    return spent.size();
  }

  @Override
  public PendingBacklog pendingBacklog(int maxAttempts) {
    failIfAsked("pendingBacklog");
    List<Row> waiting = rows.values().stream().filter(row -> row.live(maxAttempts)).toList();
    long givenUp =
        rows.values().stream().filter(row -> !row.sent && row.attempts >= maxAttempts).count();
    return new PendingBacklog(
        waiting.size(),
        waiting.stream()
            .map(row -> row.inserted.createdAt())
            .min(Comparator.naturalOrder())
            .orElse(null),
        givenUp);
  }

  // --- what a test needs to look at ------------------------------------------------------------

  /** Removes a row, standing in for the dead-letter move taking it out of the table. */
  public void remove(String eventId) {
    rows.remove(eventId);
  }

  public boolean isSent(String eventId) {
    return row(eventId).sent;
  }

  public int attemptsOf(String eventId) {
    return row(eventId).attempts;
  }

  public String leaseTokenOf(String eventId) {
    return row(eventId).leaseToken;
  }

  public Instant nextAttemptAtOf(String eventId) {
    return row(eventId).nextAttemptAt;
  }

  public Optional<OutboxInsert> written(String eventId) {
    return Optional.ofNullable(rows.get(eventId)).map(row -> row.inserted);
  }

  public List<String> eventIds() {
    return rows.keySet().stream().toList();
  }

  private Row row(String eventId) {
    Row row = rows.get(eventId);
    if (row == null) {
      throw new IllegalArgumentException(
          "no row " + eventId + "; the table holds " + rows.keySet());
    }
    return row;
  }

  private void mutate(String eventId, java.util.function.Consumer<Row> change) {
    Row row = rows.get(eventId);
    if (row != null) {
      change.accept(row);
    }
  }

  private static OutboxMessage messageOf(Row row) {
    OutboxInsert inserted = row.inserted;
    return new OutboxMessage(
        inserted.eventId(),
        inserted.source(),
        inserted.type(),
        inserted.version(),
        inserted.payload(),
        inserted.occurredAt(),
        inserted.subject(),
        inserted.tenantId(),
        inserted.correlationId(),
        inserted.causationId(),
        inserted.destination());
  }
}

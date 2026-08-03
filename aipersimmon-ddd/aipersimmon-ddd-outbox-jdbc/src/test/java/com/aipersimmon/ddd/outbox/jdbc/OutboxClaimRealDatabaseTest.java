package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import com.aipersimmon.ddd.testsupport.SharedContainers;
import com.aipersimmon.ddd.testsupport.TestDataSources;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.function.Executable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The claim protocol against real engines. Everything here already passes on H2, which is exactly
 * why it must also run on PostgreSQL and MySQL: the head-of-subject {@code NOT EXISTS} predicate
 * and the CAS lease {@code UPDATE} rest on each engine's single-statement update semantics, and for
 * a long time the only place those statements ever met a real PostgreSQL or MySQL was production.
 * The process manager's deadline claim taught the lesson (the statement was correct — but nobody
 * could have known); this closes the same gap for the outbox.
 *
 * <p>Both vendors run the same three scenarios: competing claimers partition the rows (no row won
 * twice), the head-of-subject rule holds until the head is sent, and an unexpired lease shields a
 * row from a second claimer.
 */
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class OutboxClaimRealDatabaseTest {

  private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
  private static final int MAX_ATTEMPTS = 10;

  @org.junit.jupiter.api.Test
  void postgresHonoursTheClaimContract() throws Throwable {
    DataSource dataSource = TestDataSources.from(SharedContainers.postgres());
    migrate(dataSource, "postgresql");
    runAll(dataSource);
  }

  @org.junit.jupiter.api.Test
  void mysqlHonoursTheClaimContract() throws Throwable {
    DataSource dataSource = TestDataSources.from(SharedContainers.mysql());
    migrate(dataSource, "mysql");
    runAll(dataSource);
  }

  private void runAll(DataSource dataSource) throws Throwable {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    JdbcOutboxStore store = new JdbcOutboxStore(jdbc);
    scenario(jdbc, () -> competingClaimersPartitionTheRows(store));
    scenario(jdbc, () -> onlyTheHeadOfASubjectIsClaimable(store));
    scenario(jdbc, () -> anUnexpiredLeaseShieldsARowFromASecondClaimer(store, jdbc));
  }

  private void scenario(JdbcTemplate jdbc, Executable body) throws Throwable {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    body.execute();
  }

  /**
   * Two claimers race the same table; every row must be won exactly once. The loser of a race must
   * lose on the CAS re-check ("due and unleased"), not merely on the candidate SELECT — which is
   * precisely the part H2 cannot vouch for on another engine's locking.
   */
  private void competingClaimersPartitionTheRows(JdbcOutboxStore store) throws Exception {
    for (int i = 0; i < 20; i++) {
      insert(store, "row-" + i, "subject-" + i, i);
    }
    Set<String> claimed = ConcurrentHashMap.newKeySet();
    List<String> duplicates = new ArrayList<>();
    CyclicBarrier start = new CyclicBarrier(2);
    Callable<Integer> claimer =
        () -> {
          start.await();
          int won = 0;
          OutboxLease lease =
              new OutboxLease(
                  Thread.currentThread().getName(),
                  "token-" + Thread.currentThread().getName(),
                  NOW.plus(Duration.ofMinutes(5)));
          for (List<PendingMessage> page = claim(store, lease);
              !page.isEmpty();
              page = claim(store, lease)) {
            for (PendingMessage message : page) {
              if (!claimed.add(message.message().eventId())) {
                synchronized (duplicates) {
                  duplicates.add(message.message().eventId());
                }
              }
              won++;
            }
            store.markSent(page.stream().map(m -> m.message().eventId()).toList(), NOW);
          }
          return won;
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(claimer);
      Future<Integer> b = pool.submit(claimer);
      int total = a.get() + b.get();
      assertTrue(duplicates.isEmpty(), "rows claimed twice: " + duplicates);
      assertEquals(20, total, "every row won exactly once across the two claimers");
    } finally {
      pool.shutdownNow();
    }
  }

  private void onlyTheHeadOfASubjectIsClaimable(JdbcOutboxStore store) {
    insert(store, "first", "ORDER-1", 0);
    insert(store, "second", "ORDER-1", 1);
    insert(store, "third", "ORDER-1", 2);

    OutboxLease lease = new OutboxLease("node-A", "t-1", NOW.plus(Duration.ofMinutes(5)));
    List<PendingMessage> page = claim(store, lease);
    assertEquals(1, page.size(), "only the head of the subject's queue is admitted");
    assertEquals("first", page.get(0).message().eventId());

    store.markSent(List.of("first"), NOW);
    List<PendingMessage> next =
        claim(store, new OutboxLease("node-A", "t-2", NOW.plus(Duration.ofMinutes(5))));
    assertEquals(1, next.size(), "sending the head admits the next row");
    assertEquals("second", next.get(0).message().eventId());
  }

  private void anUnexpiredLeaseShieldsARowFromASecondClaimer(
      JdbcOutboxStore store, JdbcTemplate jdbc) {
    insert(store, "held", "ORDER-9", 0);
    claim(store, new OutboxLease("node-A", "t-a", NOW.plus(Duration.ofMinutes(5))));

    assertEquals(
        0,
        claim(store, new OutboxLease("node-B", "t-b", NOW.plus(Duration.ofMinutes(5)))).size(),
        "an unexpired lease is a claim; a second poller must walk away");
    // The same row with an expired lease is fair game — a dead instance costs only its rows.
    jdbc.update(
        "UPDATE aipersimmon_outbox SET lease_until = ? WHERE event_id = 'held'",
        java.sql.Timestamp.from(NOW.minusSeconds(1)));
    assertEquals(
        1,
        claim(store, new OutboxLease("node-B", "t-c", NOW.plus(Duration.ofMinutes(5)))).size(),
        "an expired lease no longer shields the row");
  }

  private List<PendingMessage> claim(JdbcOutboxStore store, OutboxLease lease) {
    return store.claimDue(NOW, MAX_ATTEMPTS, 10, lease);
  }

  private void insert(JdbcOutboxStore store, String eventId, String subject, int offsetSeconds) {
    store.insert(
        new OutboxInsert(
            eventId,
            "/test",
            "SampleEvent",
            1,
            "{}",
            NOW.minusSeconds(3600),
            subject,
            "__root__",
            "corr",
            null,
            null,
            null,
            null,
            NOW.minusSeconds(3600).plusSeconds(offsetSeconds)));
  }

  private static void migrate(DataSource dataSource, String vendor) {
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    for (String migration :
        new String[] {
          "V1__aipersimmon_outbox.sql",
          "V2__drop_trace_id.sql",
          "V3__add_tenant_id.sql",
          "V4__relay_row_lease.sql",
          "V5__destination_on_the_row.sql"
        }) {
      populator.addScript(
          new ClassPathResource("aipersimmon/db/migration/outbox/" + vendor + "/" + migration));
    }
    populator.setContinueOnError(true); // shared container: the schema may already exist
    DatabasePopulatorUtils.execute(populator, dataSource);
  }
}

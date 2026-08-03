package com.example.samples.s11;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s11.ordering.application.ExpiredOrderSweep;
import com.example.samples.s11.ordering.application.PayOrder;
import com.example.samples.s11.ordering.application.SweepReport;
import com.example.samples.s11.ordering.infrastructure.BulkCloser;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a scheduled sweep has to get right. The trigger is off here and the tests call the work
 * directly — the split that makes that possible is the point of {@code ExpiredOrderSweepScheduler},
 * and {@link ScheduleTest} covers the trigger itself.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ordering.sweep.enabled=false", "ordering.sweep.batch-size=5"})
@Import({PostgresServiceConnection.class, Instruments.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class SweepTest {

  private static final int PAY_WITHIN_SECONDS = 60;

  @Autowired private ExpiredOrderSweep sweep;
  @Autowired private CommandBus commandBus;
  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Instruments.TestClock clock;
  @Autowired private Instruments.Dispatches dispatches;
  @Autowired private Instruments.Poison poison;
  @Autowired private Instruments.Interleave interleave;
  @Autowired private Instruments.ClosedOrders closedOrders;
  @Autowired private BulkCloser bulkCloser;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s11_order");
    dispatches.reset();
    poison.reset();
    interleave.reset();
    closedOrders.reset();
  }

  @Test
  void thesweepSendsOneCommandPerOrderRatherThanOneStatementForAll() {
    List<String> orders = place(3);
    letTheDeadlinePass();

    SweepReport report = sweep.sweepOnce();

    assertThat(report.scanned()).isEqualTo(3);
    assertThat(report.closed()).isEqualTo(3);
    assertThat(report.allSucceeded()).isTrue();
    // One command per order, and one event per order. Both are things a bulk UPDATE cannot produce.
    assertThat(dispatches.targetsFor("CloseExpiredOrder")).containsExactlyElementsOf(orders);
    assertThat(closedOrders.closedIds()).containsExactlyInAnyOrderElementsOf(orders);
    assertThat(statusesOf(orders)).containsOnly("CLOSED");
  }

  @Test
  void thebulkStatementClosesThePaidOrderTooAndTellsNobody() {
    List<String> orders = place(3);
    commandBus.send(new PayOrder(orders.get(0)));
    letTheDeadlinePass();
    closedOrders.reset();

    int rows = bulkCloser.closeEverythingOverdue(clock.instant());

    // The statement is faster and wrong in three ways at once: it closed an order that was paid, it
    // published nothing, and it never consulted the transition table that would have refused.
    assertThat(rows).isEqualTo(3);
    assertThat(statusOf(orders.get(0))).isEqualTo("CLOSED");
    assertThat(closedOrders.closedIds()).isEmpty();
  }

  @Test
  void thesweepLeavesThePaidOrderAloneWhereTheStatementWouldNot() {
    List<String> orders = place(3);
    commandBus.send(new PayOrder(orders.get(0)));
    letTheDeadlinePass();

    SweepReport report = sweep.sweepOnce();

    // Same rows, same moment, same deadline — and the paid order survives, because the aggregate was
    // asked. Note it was not even a candidate: the scan filters on status too.
    assertThat(report.scanned()).isEqualTo(2);
    assertThat(report.closed()).isEqualTo(2);
    assertThat(statusOf(orders.get(0))).isEqualTo("PAID");
  }

  @Test
  void anorderPaidBetweenTheScanAndItsCommandCountsAsSkippedNotFailed() {
    List<String> orders = place(2);
    letTheDeadlinePass();
    // The scan will propose both; this one gets paid while the round is in flight, from the moment
    // the first close command reaches the bus.
    interleave.before(() -> commandBus.send(new PayOrder(orders.get(1))));

    SweepReport report = sweep.sweepOnce();

    // The scan was advisory and the aggregate disagreed. That is not an error — and reporting it as
    // one is how a job's failure count becomes noise its operators learn to ignore.
    assertThat(report.scanned()).isEqualTo(2);
    assertThat(report.closed()).isEqualTo(1);
    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.allSucceeded()).isTrue();
    assertThat(statusOf(orders.get(1))).isEqualTo("PAID");
  }

  @Test
  void onefailingOrderDoesNotRollBackTheOthers() {
    List<String> orders = place(4);
    letTheDeadlinePass();
    poison.poison(orders.get(1));

    SweepReport report = sweep.sweepOnce();

    // Each command is its own transaction, so the failure is worth exactly one order. A single
    // batch-shaped command would have rolled back all four — and then retried all four.
    assertThat(report.closed()).isEqualTo(3);
    assertThat(report.failures()).hasSize(1);
    assertThat(report.failures().get(0).orderId()).isEqualTo(orders.get(1));
    assertThat(statusOf(orders.get(1))).isEqualTo("PLACED");
    assertThat(closedOrders.closedIds()).hasSize(3);
  }

  @Test
  void thenextRoundRetriesWhatFailedAndNothingElse() {
    List<String> orders = place(4);
    letTheDeadlinePass();
    poison.poison(orders.get(1));
    sweep.sweepOnce();
    dispatches.reset();

    poison.reset();
    SweepReport second = sweep.sweepOnce();

    // No bookkeeping made this work: the three closed orders no longer match the candidate query, and
    // the failed one still does. The scan *is* the retry mechanism, which is why it must be a
    // predicate over current state and never a queue of ids somebody has to keep in step.
    assertThat(second.scanned()).isEqualTo(1);
    assertThat(second.closed()).isEqualTo(1);
    assertThat(dispatches.targetsFor("CloseExpiredOrder")).containsExactly(orders.get(1));
    assertThat(statusOf(orders.get(1))).isEqualTo("CLOSED");
  }

  @Test
  void aroundIsBoundedAndTheBacklogDrainsOverRounds() {
    List<String> orders = place(12);
    letTheDeadlinePass();

    List<Integer> closedPerRound = new ArrayList<>();
    for (int round = 0; round < 4; round++) {
      closedPerRound.add(sweep.sweepOnce().closed());
    }

    // batch-size is 5. A round that took "everything overdue" would be fine on a normal day and
    // would be the round that locks the table on the day after an outage.
    assertThat(closedPerRound).containsExactly(5, 5, 2, 0);
    assertThat(statusesOf(orders)).containsOnly("CLOSED");
  }

  @Test
  void thescanTakesTheOldestDeadlinesFirst() {
    List<String> orders = place(3);
    letTheDeadlinePass();
    place(3);
    letTheDeadlinePass();

    sweep.sweepOnce();

    // Oldest first, so a bounded round cannot starve a backlog's tail while new work keeps arriving.
    //
    // Read this one with a caveat, established by deleting the ORDER BY and re-running: the suite
    // still passed. PostgreSQL serves this query from the index on (status, payment_due_at), whose
    // scan order happens to be the order this asserts. So the assertion documents the intent but
    // cannot enforce it — no test at this level can, because a plan is free to return any order and
    // this plan returns the right one for a reason the query did not ask for. The ORDER BY stays
    // precisely because a plan is not a contract: change the index, add a filter, upgrade the
    // planner, and an unordered query starts starving the tail with no test to notice.
    assertThat(dispatches.targetsFor("CloseExpiredOrder")).startsWith(orders.get(0));
    assertThat(statusesOf(orders)).containsOnly("CLOSED");
  }

  @Test
  void everyCommandOfOneRoundSharesOneCorrelationId() {
    place(3);
    letTheDeadlinePass();

    SweepReport report = sweep.sweepOnce();

    List<CommandContext> contexts = dispatches.contextsFor("CloseExpiredOrder");
    assertThat(contexts).hasSize(3);
    // The round is the causal root: one correlation id across every command it sent, each command
    // naming the round as its cause, and its own message id still minted by the bus. Without this the
    // 03:15 round leaves a thousand unrelated correlation ids and no way to ask what it did.
    assertThat(contexts).allSatisfy(context -> {
      assertThat(context.correlationId()).isEqualTo(report.runId());
      assertThat(context.causationId()).isEqualTo(report.runId());
    });
    assertThat(contexts.stream().map(CommandContext::messageId).distinct()).hasSize(3);
  }

  @Test
  void withNothingBoundTheRoundRunsAsTheSentinelTenant() {
    place(1);
    letTheDeadlinePass();

    sweep.sweepOnce();

    // A timer thread inherits no tenant. With multi-tenancy off, TenantContext.effective() answers
    // with the sentinel — single-tenant is N=1 multi-tenancy, so every row still has an owner. With
    // multi-tenancy on it would throw instead of quietly sweeping the wrong bucket.
    assertThat(dispatches.contextsFor("CloseExpiredOrder"))
        .allSatisfy(context -> assertThat(context.tenantId()).isEqualTo(Tenants.ROOT));
  }

  @Test
  void aroundBoundToATenantCarriesItIntoEveryCommand() {
    place(2);
    letTheDeadlinePass();

    TenantContext.runAs(Tenants.of("acme"), sweep::sweepOnce);

    // This is how a multi-tenant schedule works: one bound round per tenant, because "sweep every
    // tenant at once" is not something a tenant-scoped read can express.
    assertThat(dispatches.contextsFor("CloseExpiredOrder"))
        .allSatisfy(context -> assertThat(context.tenantId().value()).isEqualTo("acme"));
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void twoInstancesSweepingAtOnceCloseEachOrderExactlyOnce() throws Exception {
    List<String> orders = place(3);
    letTheDeadlinePass();
    // A second instance runs a whole round from the moment this one's first command reaches the bus.
    // Another thread on purpose: a nested dispatch would join this transaction instead of competing
    // with it.
    interleave.before(() -> onAnotherThread(() -> sweep.sweepOnce()));

    SweepReport report = sweep.sweepOnce();

    // No lock, no lease, no claim table. Closing is a version-checked state transition, so the loser
    // of each row is refused and counts a skip — and every order closed exactly once.
    assertThat(report.scanned()).isEqualTo(3);
    assertThat(report.closed()).isZero();
    assertThat(report.skipped()).isEqualTo(3);
    assertThat(closedOrders.closedIds()).containsExactlyInAnyOrderElementsOf(orders);
    assertThat(statusesOf(orders)).containsOnly("CLOSED");
  }

  @Test
  void theoperatorEntryRunsTheSameRoundAndReportsWhatItDid() {
    List<String> orders = place(2);
    letTheDeadlinePass();

    ResponseEntity<String> response =
        http.postForEntity("/operations/expired-order-sweep", null, String.class);

    // The fourth entry shape, over the same work, with the same report. It costs one method precisely
    // because the work was never welded to the timer.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<Integer>read(response.getBody(), "$.closed")).isEqualTo(2);
    assertThat(JsonPath.<String>read(response.getBody(), "$.runId")).isNotBlank();
    assertThat(statusesOf(orders)).containsOnly("CLOSED");
  }

  /**
   * Places orders one simulated second apart, so their deadlines are distinct and the scan's
   * "oldest first" is a total order. Ties in a sort key leave the order between them undefined —
   * the same property S20's cursor needed, for the same reason.
   */
  private List<String> place(int count) {
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      ResponseEntity<String> created =
          http.postForEntity(
              "/orders",
              Map.of("customerId", "customer-" + index, "payWithinSeconds", PAY_WITHIN_SECONDS),
              String.class);
      ids.add(JsonPath.read(created.getBody(), "$.id"));
      clock.advance(Duration.ofSeconds(1));
    }
    return ids;
  }

  private void letTheDeadlinePass() {
    clock.advance(Duration.ofSeconds(PAY_WITHIN_SECONDS + 1));
  }

  private void onAnotherThread(Runnable work) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(work).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    } finally {
      executor.shutdown();
    }
  }

  private String statusOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM s11_order WHERE id = ?", String.class, orderId);
  }

  private List<String> statusesOf(List<String> orderIds) {
    return orderIds.stream().map(this::statusOf).toList();
  }
}

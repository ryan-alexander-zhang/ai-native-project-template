package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s28.reconciliation.application.ExportSource;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where the synchronous limit actually is, measured — and it is not where it is usually quoted.
 *
 * <p>Asked about the limit on a synchronous endpoint, most answers name a timeout: the load balancer's sixty seconds,
 * the client's read timeout, the gateway's. Those are real and they are the <em>last</em> constraints to bite. What
 * bites first is the connection pool, and its arithmetic is unforgiving: concurrency × duration against a pool size,
 * with everything else in the service — including the health check — queued behind it.
 *
 * <p>The pool is turned down to 2 here with a one-second acquisition timeout, so the effect is measurable in a test
 * rather than in production. The shape of the finding does not depend on those numbers; only the size of the
 * concurrency that triggers it does.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "s28.worker.enabled=false",
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.connection-timeout=1000"
    })
@Import({PostgresServiceConnection.class, SynchronousLimitTest.SlowWork.class})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class SynchronousLimitTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private CommandBus commandBus;
  @Autowired private ExportSource source;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void seedAPeriod() {
    jdbc.update("DELETE FROM s28_export_row");
    jdbc.update(
        "INSERT INTO s28_export_row (period, order_ref, amount_cents, note)"
            + " SELECT '2026-06', 'ORD-' || g, g, 'settled' FROM generate_series(1, 200) g");
  }

  /**
   * Nothing in the library stops a handler taking as long as it likes.
   *
   * <p>Worth measuring rather than assuming, because "surely something would time it out" is the assumption that lets
   * a four-minute endpoint reach production. There is no timeout on a command, on a query, or on a handler; every
   * clock in the picture belongs to the infrastructure around the service, and none of them is consulted here.
   */
  @Test
  void thelibraryImposesNoTimeLimitOfItsOwn() {
    long started = System.nanoTime();
    commandBus.send(new TakeYourTime(Duration.ofMillis(1_200)));
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    assertThat(took).as("it simply took as long as it took").isGreaterThan(Duration.ofSeconds(1));
  }

  /**
   * Two concurrent streaming exports hold the whole pool, and an unrelated request fails.
   *
   * <p>This is the number that sets the limit. Two slow endpoints against a pool of two is the same arithmetic as ten
   * against ten, or fifty against fifty: the endpoint that takes four minutes does not have to be popular to take the
   * service down, it only has to be as concurrent as the pool is wide.
   *
   * <p>Note what the failure looks like: not a timeout on the slow endpoint, but a connection-acquisition failure on an
   * unrelated one. Which is why the cause is hard to find from the symptom — the endpoint that breaks is never the
   * endpoint at fault.
   */
  @Test
  void twoSlowExportsExhaustThePoolAndAnUnrelatedQueryFails() throws Exception {
    CountDownLatch bothInside = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> unrelatedFailure = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 2; i++) {
        pool.submit(() -> holdAConnectionWhileStreaming(bothInside, release));
      }
      assertThat(bothInside.await(20, TimeUnit.SECONDS)).as("both exports started").isTrue();
      try {
        jdbc.queryForObject("SELECT 1", Integer.class);
      } catch (RuntimeException e) {
        unrelatedFailure.set(e);
      }
    } finally {
      release.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(unrelatedFailure.get())
        .as("a trivial query, nothing to do with exports, could not get a connection")
        .isInstanceOfAny(
            CannotCreateTransactionException.class, DataAccessResourceFailureException.class);
  }

  /** And it recovers on its own the moment the slow work lets go — no restart, no intervention. */
  @Test
  void thepoolIsFineAgainAsSoonAsTheSlowWorkFinishes() {
    assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    StringWriter out = new StringWriter();
    new TransactionTemplate(transactions)
        .executeWithoutResult(status -> source.streamPeriod("2026-06", row -> out.write("x")));
    assertThat(out.toString()).hasSize(200);
    assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  /** Stream the period, then sit on the connection until the test says to let go. */
  private void holdAConnectionWhileStreaming(CountDownLatch inside, CountDownLatch release) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status ->
                source.streamPeriod(
                    "2026-06",
                    row -> {
                      if (inside.getCount() > 0) {
                        inside.countDown();
                        try {
                          release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }
                    }));
  }

  /**
   * A command that does nothing slowly, so the absence of any library-imposed limit can be measured.
   *
   * <p>Test scope, because a sample must not ship a deliberately slow endpoint. The subject is the framework's
   * silence, not this command.
   */
  record TakeYourTime(Duration howLong) implements Command<Void> {}

  @TestConfiguration(proxyBeanMethods = false)
  static class SlowWork {

    @Bean
    TakeYourTimeHandler takeYourTimeHandler() {
      return new TakeYourTimeHandler();
    }
  }

  static class TakeYourTimeHandler implements CommandHandler<TakeYourTime, Void> {

    @Override
    public Void handle(TakeYourTime command, CommandContext context) {
      try {
        Thread.sleep(command.howLong().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}

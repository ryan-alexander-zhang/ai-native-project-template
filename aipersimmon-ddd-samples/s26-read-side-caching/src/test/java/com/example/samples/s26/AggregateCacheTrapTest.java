package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Caching the aggregate, and the two things that actually break.
 *
 * <p>Neither of them is a lost update. Optimistic locking holds: a write from a stale version is refused and
 * the database stays consistent, which is exactly why this mistake survives — the failure is not in the data,
 * it is in what the application is told and in what it can recover from.
 *
 * <p>All three tests below use the real command bus, the real repository underneath, and the real
 * retry-on-conflict interceptor this deployment enables. The only change is {@link CachedProducts}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({
  PostgresServiceConnection.class,
  RedisServiceConnection.class,
  ControllableCache.class,
  SlowReads.class,
  CachedProducts.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class AggregateCacheTrapTest extends CacheTestBase {

  @Autowired private Products products;
  @Autowired private CachedProducts.Memoising memoising;

  @BeforeEach
  void emptyTheAggregateCache() {
    memoising.clear();
  }

  /**
   * A change nobody saved becomes the next reader's starting point.
   *
   * <p>One instance, handed to everybody. A command that mutates the aggregate and then fails — a validation
   * error after the first mutation, an exception from a second aggregate, a rollback — leaves the mutation in
   * the shared instance, and the next command builds on a state the database never had. The transaction rolled
   * back; the object did not.
   *
   * <p>The second half of the test is its own control: emptying the map produces a fresh instance with the
   * committed name, which is what proves the leak came from the memoisation rather than from anything else in
   * the stack.
   */
  @Test
  void achangeThatWasNeverSavedLeaksToTheNextReader() {
    Product first = products.find(new Sku(KEYBOARD)).orElseThrow();
    first.renameTo("Half Done");

    Product next = products.find(new Sku(KEYBOARD)).orElseThrow();

    assertThat(next.name()).isEqualTo("Half Done");
    assertThat(storedName(KEYBOARD)).isEqualTo("Keyboard");

    memoising.clear();
    assertThat(products.find(new Sku(KEYBOARD)).orElseThrow().name()).isEqualTo("Keyboard");
  }

  /**
   * {@code version()} stops describing the database.
   *
   * <p>The version is the write path's concurrency token: the repository writes {@code WHERE version = loaded}
   * and refuses the update if it matches nothing. Cached, the number it compares against is whatever this
   * process last saw, so the guard is still enforced — against a value it made up.
   */
  @Test
  void theversionBecomesAFactAboutTheCacheAndNotTheRow() {
    products.find(new Sku(KEYBOARD));

    jdbc.update("UPDATE s26_product SET name = 'Moved On', version = version + 1 WHERE sku = ?", KEYBOARD);

    assertThat(products.find(new Sku(KEYBOARD)).orElseThrow().version()).isEqualTo(1);
    assertThat(rowVersion(KEYBOARD)).isEqualTo(2);
  }

  /**
   * And here is what that costs: a command that reports success and writes nothing.
   *
   * <p>Follow it through. The cached instance is at version 1. Another writer moves the row to version 2. The
   * rename mutates the cached instance to {@code Mechanical Keyboard} and its save matches no row, so the
   * conflict is raised — correctly — and the retry interceptor reruns the command, which is the one retry that
   * is <em>supposed</em> to succeed because a rerun reloads the aggregate at its new version. Except the reload
   * comes from the map, so it returns the same instance, which has already been renamed. {@code renameTo} now
   * returns false, the handler treats that as "nothing to do", and the command completes.
   *
   * <p>The caller gets a success. The database still says {@code Moved On}. No exception, no log, no conflict
   * reported to anyone — the only trace is that the rename did not happen. Compare
   * {@link AggregateWriteWithoutTheTrapTest}, where the identical sequence writes what it was asked to.
   */
  @Test
  void aconflictBecomesASuccessThatWroteNothing() {
    products.find(new Sku(KEYBOARD));

    jdbc.update("UPDATE s26_product SET name = 'Moved On', version = version + 1 WHERE sku = ?", KEYBOARD);

    rename(KEYBOARD, "Mechanical Keyboard");

    assertThat(storedName(KEYBOARD)).isEqualTo("Moved On");
    assertThat(rowVersion(KEYBOARD)).isEqualTo(2);
  }

  private long rowVersion(String sku) {
    return jdbc.queryForObject("SELECT version FROM s26_product WHERE sku = ?", Long.class, sku);
  }
}

package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.example.samples.s26.catalog.domain.ProductRenamed;
import com.example.samples.s26.catalog.domain.ProductRepriced;
import com.example.samples.s26.catalog.domain.Sku;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drop the product's cached detail when the product changes — <em>after</em> the change is visible to
 * everybody else.
 *
 * <p>Both variants below are present, selected by {@code s26.cache.invalidate}, because one of them is a
 * bug and a bug nobody can run is a bug nobody believes. The sequence that breaks
 * {@link Eager} is short enough to hold in the head:
 *
 * <ol>
 *   <li>A writer renames the product and evicts the entry, still inside its transaction.
 *   <li>A reader misses, reads the database — which still shows the <em>old</em> name, because the
 *       writer has not committed — and stores it.
 *   <li>The writer commits.
 * </ol>
 *
 * <p>The cache now holds the old name, the eviction that should have removed it has already happened,
 * and nothing will correct it until the TTL expires. The window is small; the damage lasts a whole TTL,
 * which is the asymmetry that makes it worth being exact about.
 *
 * <p>{@link AfterCommit} closes that ordering, and it is worth being precise that it does not close
 * <em>every</em> ordering: a reader whose store lands after the eviction — because its own write to Redis
 * was slow, or because it read the database before the commit and got descheduled — still leaves a stale
 * entry behind. Eviction is not atomic with the commit and cannot be made so without a distributed
 * transaction across Postgres and Redis. That is why <strong>the TTL is not decoration but the only
 * bound on how wrong the cache can be</strong>, and why an unbounded-TTL cache is a cache that will
 * eventually be permanently wrong about something. {@code InvalidationAfterCommitTest} constructs that
 * residual window on purpose.
 *
 * <p>Note also what is <em>not</em> here: no listener for a sale. See {@link ProductDetail}.
 *
 * <p>Both classes carry {@code @DomainEventHandler}, but only one of them was made to. The library's
 * architecture rule that requires the marker matches {@code @EventListener} by name and directly only, and
 * {@code @TransactionalEventListener} carries it as a meta-annotation — so {@link Eager} was rejected by
 * {@code AiPersimmonDddRules.all()} and {@link AfterCommit}, whose annotation is the one the library's own
 * documentation recommends, was not looked at. Filed as issue-00166; {@code ArchitectureTest} in this sample
 * writes the meta-annotation-aware version of the rule and enforces it over both.
 */
public final class ProductCacheInvalidation {

  private ProductCacheInvalidation() {}

  /**
   * Evicts once the transaction has committed. The correct one.
   *
   * <p>It relies on {@code AFTER_COMMIT} delivery being <em>synchronous on the committing thread</em>,
   * which Spring's is. That is not a detail: the key is built from {@code TenantContext}, which is
   * thread-bound, so an eviction moved onto an executor would compute the sentinel tenant's key and
   * cheerfully delete nothing — a silent failure with a passing test suite. Anything asynchronous here
   * has to carry the tenant with it.
   */
  @Component
  @DomainEventHandler
  @ConditionalOnProperty(
      prefix = "s26.cache",
      name = "invalidate",
      havingValue = "AFTER_COMMIT",
      matchIfMissing = true)
  static class AfterCommit {

    private final QueryCache cache;
    private final CacheTelemetry telemetry;

    AfterCommit(QueryCache cache, CacheTelemetry telemetry) {
      this.cache = cache;
      this.telemetry = telemetry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void renamed(ProductRenamed event) {
      evict(event.sku());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void repriced(ProductRepriced event) {
      evict(event.sku());
    }

    /**
     * Keyed on the aggregate, not on what changed about it.
     *
     * <p>Two event types, one eviction, because the cache entry is "the detail of this product" and every
     * change to the product invalidates all of it. An eviction per attribute would be a list to maintain
     * for the rest of the read model's life, and the first field somebody forgets to add to it is stale
     * for a TTL with nothing to notice.
     */
    private void evict(Sku sku) {
      cache.evict(CacheKeys.current(new ProductDetailQuery(sku.value()).cacheKey()));
      telemetry.evicted();
    }
  }

  /**
   * Evicts inside the transaction. Present so the failure can be measured, not because it is an option.
   *
   * <p>It is also the shape somebody arrives at naturally: {@code @EventListener} is the shorter
   * annotation, it fires immediately, and in every test that does not interleave a reader it behaves
   * identically to the correct one. That is what makes it worth a test of its own —
   * {@code InvalidationInTransactionTest} is red-by-design in the sense that it asserts a stale read
   * really happens.
   */
  @Component
  @DomainEventHandler
  @ConditionalOnProperty(prefix = "s26.cache", name = "invalidate", havingValue = "IN_TRANSACTION")
  static class Eager {

    private final QueryCache cache;
    private final CacheTelemetry telemetry;

    Eager(QueryCache cache, CacheTelemetry telemetry) {
      this.cache = cache;
      this.telemetry = telemetry;
    }

    @EventListener
    void renamed(ProductRenamed event) {
      evict(event.sku());
    }

    @EventListener
    void repriced(ProductRepriced event) {
      evict(event.sku());
    }

    private void evict(Sku sku) {
      cache.evict(CacheKeys.current(new ProductDetailQuery(sku.value()).cacheKey()));
      telemetry.evicted();
    }
  }
}

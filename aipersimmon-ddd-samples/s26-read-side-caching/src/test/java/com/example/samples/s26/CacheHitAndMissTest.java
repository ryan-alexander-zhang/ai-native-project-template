package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.example.samples.s26.catalog.application.ProductDetail;
import com.example.samples.s26.catalog.application.TopSellersQuery;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** What the interceptor does, measured in trips to the database rather than in hit ratios. */
class CacheHitAndMissTest extends CacheTestBase {

  @Test
  void thefirstReadPaysAndTheSecondDoesNot() {
    ProductDetail first = detail(KEYBOARD);
    ProductDetail second = detail(KEYBOARD);

    assertThat(second).isEqualTo(first);
    assertThat(telemetry.getMisses()).isEqualTo(1);
    assertThat(telemetry.getHits()).isEqualTo(1);
    // The number that matters: one read of the source for two answers.
    assertThat(telemetry.getDatabaseReads()).isEqualTo(1);
  }

  /**
   * A hit really does not reach the handler, proved by changing the database where nothing will notice.
   *
   * <p>{@code renameBehindTheCachesBack} writes the row with plain SQL, so no aggregate is loaded, no domain
   * event is recorded and no eviction happens. If the second read still says "Keyboard" then the handler did
   * not run — there is no other way to get the old value once the row has changed. Asserting on a counter
   * alone would leave open that the handler ran and the counter was wrong.
   */
  @Test
  void ahitAnswersWithoutTheHandlerRunningAtAll() {
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");

    renameBehindTheCachesBack(KEYBOARD, "Something Else");

    assertThat(storedName(KEYBOARD)).isEqualTo("Something Else");
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");
    assertThat(telemetry.getDatabaseReads()).isEqualTo(1);
  }

  /**
   * A query that does not wear {@code CachedQuery} is not cached, and the interceptor does not even count it.
   *
   * <p>This is the opt-in default made visible. The best-sellers query passes through the same bus, the same
   * chain and the same interceptor; because it does not declare itself cacheable, none of the cache's
   * counters move at all — not even a miss.
   */
  @Test
  void anuncachedQueryIsUntouchedByTheInterceptor() {
    queryBus.ask(new TopSellersQuery(5));
    queryBus.ask(new TopSellersQuery(5));

    assertThat(telemetry.getHits()).isZero();
    assertThat(telemetry.getMisses()).isZero();
  }

  /**
   * Every entry expires, and inside the jitter band.
   *
   * <p>The TTL is the only bound on how wrong the cache can get once an eviction has been lost or overtaken,
   * so an entry without one is not a cache entry. The band is the configured 60s ±10%.
   */
  @Test
  void theentryExpiresOnItsOwnWithinTheJitterBand() {
    detail(KEYBOARD);

    assertThat(cache.timeToLive(keyOf(KEYBOARD)))
        .isPresent()
        .get()
        .satisfies(
            ttl -> {
              assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(66));
              assertThat(ttl).isGreaterThan(Duration.ofSeconds(53));
            });
  }

  /**
   * Absence is not cached, and the second ask pays again.
   *
   * <p>The alternative — negative caching — would stop a flood of requests for a nonexistent sku from
   * reaching the database, and would let anyone fill the keyspace with keys of their own choosing. Paying for
   * the miss is the cheaper of the two, and this asserts the choice rather than leaving it to be inferred
   * from the absence of code.
   */
  @Test
  void anotFoundIsNotStored() {
    assertThatThrownBy(() -> detail("sku-nothing")).isInstanceOf(EntityNotFoundException.class);

    assertThat(cache.get(keyOf("sku-nothing"))).isEmpty();

    assertThatThrownBy(() -> detail("sku-nothing")).isInstanceOf(EntityNotFoundException.class);
    assertThat(telemetry.getDatabaseReads()).isEqualTo(2);
  }

  /** Two products, two keys. Obvious, and the thing a key built from the wrong inputs gets wrong. */
  @Test
  void eachProductHasItsOwnEntry() {
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");
    assertThat(detail(MOUSE).name()).isEqualTo("Mouse");

    assertThat(cache.get(keyOf(KEYBOARD))).isPresent();
    assertThat(cache.get(keyOf(MOUSE))).isPresent();
    assertThat(telemetry.getDatabaseReads()).isEqualTo(2);
  }
}

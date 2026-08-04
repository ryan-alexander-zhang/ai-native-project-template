package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.samples.s26.catalog.domain.Sku;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * One cached value with two consistency guarantees, both asserted.
 *
 * <p>The TTL here is one second so the bound can be measured rather than described. The point being made is
 * not that a short TTL works — it is that <strong>the two halves of {@code ProductDetail} were given
 * different guarantees on purpose</strong>, and that the choice is visible in behaviour: a rename shows up at
 * once, a sale shows up within a TTL, and the projection beside the cache is already right about both.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "s26.cache.ttl.ProductDetailQuery=1s")
class BoundedStalenessTest extends CacheTestBase {

  /**
   * A sale does not evict, so the cached figure is deliberately behind — and the projection is not.
   *
   * <p>Both numbers are read in the same instant from the same process. The cache says 0 and the projection
   * says 5, and neither is a defect: the cache is honouring a TTL that was chosen because sales are the
   * highest-volume write in the system, and the projection is fresh because it paid for that on the write
   * path. This one assertion is the whole cache-versus-projection trade, in numbers.
   */
  @Test
  void asaleLeavesTheCachedFigureBehindWhileTheProjectionIsAlreadyRight() {
    assertThat(detail(KEYBOARD).soldRecently()).isZero();

    sell(KEYBOARD, 5);

    assertThat(detail(KEYBOARD).soldRecently()).isZero();
    assertThat(salesBoard.soldRecently(new Sku(KEYBOARD))).contains(5L);
  }

  /** And it catches up on its own, which is what makes the staleness bounded rather than indefinite. */
  @Test
  void thefigureCatchesUpWithinTheTtl() {
    detail(KEYBOARD);
    sell(KEYBOARD, 5);

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(detail(KEYBOARD).soldRecently()).isEqualTo(5));
  }

  /**
   * A rename is not left to the TTL. It is visible on the next read.
   *
   * <p>The other half of the choice: the fields the catalogue owns are evicted, so no bound applies to them at
   * all. If this were also TTL-bounded, a price correction would take a minute to reach a customer, and the
   * TTL could never be raised however much the cache was worth.
   */
  @Test
  void arenameIsVisibleImmediatelyWithoutWaitingForTheTtl() {
    assertThat(detail(KEYBOARD).name()).isEqualTo("Keyboard");

    rename(KEYBOARD, "Mechanical Keyboard");

    assertThat(detail(KEYBOARD).name()).isEqualTo("Mechanical Keyboard");
  }

  /** So is a reprice, and by the same one eviction — the entry is keyed on the product, not the field. */
  @Test
  void arepriceIsVisibleImmediatelyToo() {
    assertThat(detail(KEYBOARD).priceCents()).isEqualTo(4500);

    reprice(KEYBOARD, 3900);

    assertThat(detail(KEYBOARD).priceCents()).isEqualTo(3900);
    assertThat(telemetry.getEvictions()).isEqualTo(1);
  }
}

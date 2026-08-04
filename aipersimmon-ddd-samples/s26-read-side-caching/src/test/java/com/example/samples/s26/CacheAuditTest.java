package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s26.catalog.application.CacheAudit;
import com.example.samples.s26.catalog.domain.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * How anyone would find out that the cache has stopped telling the truth.
 *
 * <p>Worth noticing what the divergence in the first test does to the other metrics: the hit ratio goes
 * <em>up</em>, because nothing is being evicted, and latency improves for the same reason. A cache that has
 * silently stopped being invalidated looks, on a dashboard, like a cache that has finally started working.
 * That is why the comparison has to be made deliberately rather than inferred.
 */
class CacheAuditTest extends CacheTestBase {

  @Autowired private CacheAudit audit;

  /**
   * A row changed without an eviction, and the audit says so.
   *
   * <p>{@code renameBehindTheCachesBack} is the shape of every real cause: a migration, a support script, a
   * second writer that does not go through the command bus, or a lost eviction. None of them announce
   * themselves.
   */
  @Test
  void adivergenceIsReported() {
    detail(KEYBOARD);

    renameBehindTheCachesBack(KEYBOARD, "Something Else");

    assertThat(audit.check(new Sku(KEYBOARD)))
        .isPresent()
        .get()
        .satisfies(
            divergence -> {
              assertThat(divergence.cached().name()).isEqualTo("Keyboard");
              assertThat(divergence.actual().name()).isEqualTo("Something Else");
            });
    assertThat(telemetry.getDivergences()).isEqualTo(1);
  }

  /** And an agreeing entry is not reported, so the counter above is not counting every check. */
  @Test
  void anagreeingEntryIsNotADivergence() {
    detail(KEYBOARD);

    assertThat(audit.check(new Sku(KEYBOARD))).isEmpty();
    assertThat(telemetry.getDivergences()).isZero();
  }

  /**
   * No entry is not a divergence either.
   *
   * <p>A cache with nothing in it is a cache that is about to be right, so reporting absence would make the
   * one number that needs an alert on it fire constantly — most keys are cold most of the time.
   */
  @Test
  void anabsentEntryIsNotADivergence() {
    assertThat(audit.check(new Sku(KEYBOARD))).isEmpty();
    assertThat(telemetry.getDivergences()).isZero();
  }

  /**
   * A rename through the command bus leaves nothing to diverge.
   *
   * <p>The sibling control for the first test: the same rename, done the supported way, is invisible to the
   * audit. Which means the audit is detecting the missing eviction and not merely detecting that a rename
   * happened.
   */
  @Test
  void arenameThroughTheCommandBusDoesNotDiverge() {
    detail(KEYBOARD);

    rename(KEYBOARD, "Mechanical Keyboard");

    assertThat(audit.check(new Sku(KEYBOARD))).isEmpty();
    assertThat(telemetry.getDivergences()).isZero();
  }
}

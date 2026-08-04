package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s26.catalog.application.CacheKeys;
import org.junit.jupiter.api.Test;

/**
 * Two tenants asking about the same sku get two entries, and one can be flushed without the other.
 *
 * <p>The rows in this sample are not tenant-scoped — the discriminator column is S13's subject, inside S4 —
 * so both tenants here read the same product and get the same answer. That is deliberate and it does not
 * weaken the test: what is being checked is that the <em>cache</em> keeps them apart, which is the part a
 * cache gets wrong on its own. A read model that is tenant-scoped in the database and not in the cache is
 * isolated everywhere except in the one place nobody looks.
 */
class TenantIsolationTest extends CacheTestBase {

  private static final TenantId ACME = Tenants.of("acme");
  private static final TenantId BETA = Tenants.of("beta");

  @Test
  void thesameSkuUnderTwoTenantsIsTwoEntries() {
    TenantContext.runAs(ACME, () -> detail(KEYBOARD));
    TenantContext.runAs(BETA, () -> detail(KEYBOARD));

    assertThat(cache.get(CacheKeys.of(ACME, "product-detail:" + KEYBOARD))).isPresent();
    assertThat(cache.get(CacheKeys.of(BETA, "product-detail:" + KEYBOARD))).isPresent();
    // Two misses, two reads: neither tenant was served the other's copy.
    assertThat(telemetry.getHits()).isZero();
    assertThat(telemetry.getDatabaseReads()).isEqualTo(2);
  }

  @Test
  void asecondReadByTheSameTenantHits() {
    TenantContext.runAs(ACME, () -> detail(KEYBOARD));
    TenantContext.runAs(ACME, () -> detail(KEYBOARD));

    assertThat(telemetry.getHits()).isEqualTo(1);
    assertThat(telemetry.getDatabaseReads()).isEqualTo(1);
  }

  /**
   * A flush is one tenant's, not everybody's.
   *
   * <p>This is the operational payoff of the tenant leading the key. Without it the only available blast radius
   * is the whole keyspace, and on a Redis shared with other services that is an outage for people who have
   * never heard of this one.
   */
  @Test
  void aflushIsScopedToOneTenant() {
    TenantContext.runAs(ACME, () -> detail(KEYBOARD));
    TenantContext.runAs(BETA, () -> detail(KEYBOARD));

    int removed = cache.evictMatching(CacheKeys.allOf(ACME));

    assertThat(removed).isEqualTo(1);
    assertThat(cache.get(CacheKeys.of(ACME, "product-detail:" + KEYBOARD))).isEmpty();
    assertThat(cache.get(CacheKeys.of(BETA, "product-detail:" + KEYBOARD))).isPresent();
  }
}

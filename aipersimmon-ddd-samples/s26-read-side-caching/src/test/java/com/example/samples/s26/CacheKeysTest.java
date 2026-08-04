package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s26.catalog.application.CacheKeys;
import com.example.samples.s26.catalog.application.ProductDetailQuery;
import org.junit.jupiter.api.Test;

/**
 * The key, and the collision it would have had.
 *
 * <p>Every claim here is paired with its control: the ambiguity is demonstrated against a raw join before
 * it is shown to be refused, because "this cannot happen" asserted alone is indistinguishable from "I did
 * not try".
 */
class CacheKeysTest {

  private static final TenantId ACME = Tenants.of("acme");

  @Test
  void thetenantLeadsTheKey() {
    String key = CacheKeys.of(ACME, new ProductDetailQuery("sku-keyboard").cacheKey());

    assertThat(key).isEqualTo("s26:q:acme:product-detail:sku-keyboard");
    // Which is what makes a tenant-scoped flush expressible at all.
    assertThat(key).startsWith(CacheKeys.allOf(ACME).replace("*", ""));
  }

  /**
   * The control: a raw join really does collide.
   *
   * <p>Two different tenants, two different query keys, one string. Nothing about this is exotic — it is
   * what happens whenever two segments of a joined key may both contain the separator, and the reason it
   * is worth a test is that the collision is silent: the second tenant to arrive is served the first's
   * value with no error, no log and no metric.
   */
  @Test
  void arawJoinLetsOneTenantReadAnothersEntry() {
    String victim = rawJoin("acme", "b:product-detail:sku-keyboard");
    String attacker = rawJoin("acme:b", "product-detail:sku-keyboard");

    assertThat(attacker).isEqualTo(victim);
  }

  /** And the fix: the segment the deployment controls may not contain the separator. */
  @Test
  void aseparatorInTheTenantIsRefused() {
    TenantId ambiguous = Tenants.fromValue("acme:b");

    assertThatThrownBy(() -> CacheKeys.of(ambiguous, "product-detail:sku-keyboard"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separator")
        .hasMessageContaining("ambiguous");
  }

  /**
   * A sku may contain the separator, and that stays safe.
   *
   * <p>It has to: a sku is somebody else's catalogue's business. Because the tenant cannot contain a
   * separator, the key is still readable left to right — the first segment after the prefix is the tenant
   * and everything after it belongs to the query, however many colons that contains.
   */
  @Test
  void afreeFormSkuIsStillUnambiguous() {
    String odd = CacheKeys.of(ACME, new ProductDetailQuery("part:12:rev:b").cacheKey());

    assertThat(odd).isEqualTo("s26:q:acme:product-detail:part:12:rev:b");
    assertThat(odd).startsWith("s26:q:acme:");
  }

  /**
   * With no tenant bound and multi-tenancy off, keys carry the library's sentinel rather than nothing.
   *
   * <p>Single-tenant is N=1 multi-tenancy, so there is one key shape and not two. The day tenancy is
   * switched on, no key format changes and no cached entry has to be interpreted two ways.
   */
  @Test
  void anunboundTenantResolvesToTheSentinel() {
    TenantContext.clear();

    assertThat(CacheKeys.current("product-detail:x"))
        .isEqualTo("s26:q:" + Tenants.ROOT.value() + ":product-detail:x");
  }

  @Test
  void aboundTenantIsTheOneUsed() {
    String key = TenantContext.runAs(ACME, () -> CacheKeys.current("product-detail:x"));

    assertThat(key).isEqualTo("s26:q:acme:product-detail:x");
  }

  /** The shape the guard exists to prevent, written out so the test above is not asserting a tautology. */
  private static String rawJoin(String tenant, String queryKey) {
    return CacheKeys.PREFIX + tenant + CacheKeys.SEPARATOR + queryKey;
  }
}

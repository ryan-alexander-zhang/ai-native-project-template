package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;

/**
 * Where a cache key comes from, and why concatenating identities is not as safe as it looks.
 *
 * <p><strong>The hazard is ambiguity, not ordering.</strong> A key built by joining variable-length
 * segments with a separator can be parsed more than one way as soon as <em>two</em> of its segments may
 * contain that separator: joining tenant {@code a} with query key {@code b:c} and joining tenant
 * {@code a:b} with query key {@code c} produce the identical string. Whichever of those two arrives
 * second reads the other's entry, and between two tenants that is a data-isolation failure with no error
 * anywhere. Putting the tenant first does not fix it — the collision above has the tenant first.
 *
 * <p>What fixes it is making at most one segment free-form. Here the tenant is checked to contain no
 * separator, so the key can be read left to right without ambiguity: prefix, tenant, then everything
 * after the first separator is the query's own key. That check is cheap and it is not paranoia — a tenant
 * id is often derived from something a customer chose (a slug, a subdomain, an imported organisation
 * name), and the library deliberately does not constrain its characters: {@code Tenants.of} rejects only
 * the reserved {@code __} prefix and a length over 32. {@code CacheKeysTest} builds the collision against
 * a raw join to show it is real, then shows this refuses it.
 *
 * <p>The tenant still goes first, for a different and simpler reason: a prefix is the only cheap way to
 * name a <em>group</em> of entries, so whatever leads the key is the only thing an operator can flush as a
 * unit. Tenant first makes "drop tenant t1's cache" expressible; query-type first would have made "drop
 * every tenant's product details" expressible instead, which is the less useful of the two.
 *
 * <p>{@link TenantContext#effective()} is where the tenant comes from, and the library is explicit that
 * this is the read side's source: the {@code CommandContext} is the write-side authority, and a query has
 * no {@code CommandContext} — the {@code QueryInterceptor} contract does not carry one, by design. With
 * multi-tenancy switched off (this sample's default) {@code effective()} returns the {@code __root__}
 * sentinel, so single-tenant is N=1 rather than a separate code path and the keys have one shape either
 * way.
 */
public final class CacheKeys {

  /**
   * Namespaces this sample's entries inside a Redis instance that may hold others'. The library's own
   * Redis-backed stores do the same ({@code aipersimmon:web:idem:}), for the same reason: a keyspace
   * without a namespace is one careless flush away from taking somebody else's data with it.
   */
  public static final String PREFIX = "s26:q:";

  /** The one character that may not appear in a trusted segment. */
  public static final char SEPARATOR = ':';

  private CacheKeys() {}

  /** The key for {@code queryKey} under the tenant bound to this thread. */
  public static String current(String queryKey) {
    return of(TenantContext.effective(), queryKey);
  }

  /**
   * The key for {@code queryKey} under an explicit tenant.
   *
   * @throws IllegalArgumentException if the tenant contains {@link #SEPARATOR}, which would make the key
   *     ambiguous against another tenant's
   */
  public static String of(TenantId tenant, String queryKey) {
    return PREFIX + requireUnambiguous(tenant) + SEPARATOR + queryKey;
  }

  /** Every entry belonging to one tenant, as a glob for {@link QueryCache#evictMatching}. */
  public static String allOf(TenantId tenant) {
    return PREFIX + requireUnambiguous(tenant) + SEPARATOR + "*";
  }

  private static String requireUnambiguous(TenantId tenant) {
    String value = tenant.value();
    if (value.indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException(
          "tenant id '"
              + value
              + "' contains the cache key separator '"
              + SEPARATOR
              + "', which would make its keys ambiguous against another tenant's: joining tenant 'a'"
              + " with query key 'b:c' and tenant 'a:b' with query key 'c' produce the same string, so"
              + " one tenant would read the other's entries. Either forbid the separator when tenants"
              + " are provisioned, or encode the segments (length prefixes, or a hash) instead of"
              + " joining them.");
    }
    return value;
  }
}

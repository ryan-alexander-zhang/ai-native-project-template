package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.TenantResolutionContext;
import com.aipersimmon.ddd.tenancy.TenantResolver;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Optional;

/**
 * The default {@link TenantResolver}: reads the tenant from a configured request header. A present
 * but invalid value (blank, too long, or using the reserved {@code __} prefix) makes {@link
 * Tenants#of(String)} throw, which the {@link TenantResolutionFilter} turns into a rejected
 * request; an absent header resolves to empty, which the filter handles via the {@link
 * com.aipersimmon.ddd.tenancy.MissingTenantPolicy}. Consumers needing subdomain/JWT resolution
 * provide their own {@link TenantResolver} bean.
 */
public final class HeaderTenantResolver implements TenantResolver {

  private final String headerName;

  public HeaderTenantResolver(String headerName) {
    this.headerName = headerName;
  }

  @Override
  public Optional<TenantId> resolve(TenantResolutionContext context) {
    return context.header(headerName).map(Tenants::of);
  }
}

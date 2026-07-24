package com.aipersimmon.ddd.tenancy;

import java.util.Optional;

/**
 * Resolves the {@link TenantId} for an inbound request from a {@link TenantResolutionContext}.
 * Returns empty when no tenant can be determined; the caller then applies the {@link
 * MissingTenantPolicy}.
 */
@FunctionalInterface
public interface TenantResolver {

  Optional<TenantId> resolve(TenantResolutionContext context);
}

/**
 * Framework-free multi-tenancy primitives for the DDD building blocks.
 *
 * <p>Multi-tenancy uses the pool model — a shared schema with a non-null {@code tenant_id}
 * discriminator column — so single-tenant is simply N=1 (see {@code decision-00018} / {@code
 * design-00009}). This module holds only the primitives; the actual isolation is woven into the
 * existing cqrs / integration / outbox / process-manager / web-store components.
 *
 * <ul>
 *   <li>{@link com.aipersimmon.ddd.tenancy.TenantId} — the data-isolation boundary identifier, an
 *       immutable, bounded-length, opaque value.
 *   <li>{@link com.aipersimmon.ddd.tenancy.Tenants} — the {@code __root__} sentinel and the {@code
 *       of(String)} factory that rejects the reserved {@code __} prefix for user tenants.
 *   <li>{@link com.aipersimmon.ddd.tenancy.TenantContext} — the request-scoped ambient holder,
 *       analogous to SLF4J's MDC: bound once at a trusted boundary, read by the read side and
 *       infrastructure, always cleared when the scope ends. The write-side authority is {@code
 *       CommandContext.tenantId}; this holder carries identity, not per-command state.
 *   <li>{@link com.aipersimmon.ddd.tenancy.TenantResolver} / {@link
 *       com.aipersimmon.ddd.tenancy.TenantResolutionContext} / {@link
 *       com.aipersimmon.ddd.tenancy.MissingTenantPolicy} — how a tenant is resolved from an inbound
 *       request and what happens when none resolves.
 * </ul>
 *
 * <p>Hard rule: zero framework dependencies (only the {@code aipersimmon-ddd-core} Identifier
 * marker). The Spring edge filter and the CommandContext binding live in the optional {@code
 * aipersimmon-ddd-tenancy-spring} module.
 */
package com.aipersimmon.ddd.tenancy;

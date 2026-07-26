/**
 * Spring wiring for multi-tenancy.
 *
 * <ul>
 *   <li>{@link com.aipersimmon.ddd.tenancy.spring.TenantResolutionFilter} — the edge {@code
 *       OncePerRequestFilter} that resolves the tenant from an inbound request (via a {@link
 *       com.aipersimmon.ddd.tenancy.TenantResolver}), applies the {@link
 *       com.aipersimmon.ddd.tenancy.MissingTenantPolicy}, binds it into the {@link
 *       com.aipersimmon.ddd.tenancy.TenantContext} and MDC for the request, and clears both
 *       afterwards. Mirrors the request-id filter and registers just after it.
 *   <li>{@link com.aipersimmon.ddd.tenancy.spring.TenantContextCommandInterceptor} — re-binds the
 *       ambient {@code TenantContext} from a command's {@code CommandContext.tenantId} for the
 *       whole handling, so dispatches with no ambient tenant (durable relay, batch, scheduler)
 *       still run under the command's tenant.
 * </ul>
 *
 * <p>The whole resolution activates only when {@code aipersimmon.ddd.tenancy.enabled=true}; a
 * single-tenant deployment behaves exactly as it did before tenancy existed (commands run under the
 * {@code __root__} sentinel seeded by the command bus).
 */
package com.aipersimmon.ddd.tenancy.spring;

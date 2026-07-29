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
 *   <li>{@link com.aipersimmon.ddd.tenancy.spring.TenantContextTaskDecorator} — carries the tenant
 *       across a thread hop for the executor Spring Boot auto-configures, so {@code @Async} work
 *       keeps the submitting request's binding. Backs off when the application owns a decorator.
 *   <li>A {@link com.aipersimmon.ddd.tenancy.TenantEnforcement} bean bound to the context
 *       lifecycle, which makes a missing binding an error rather than a fall back to the sentinel.
 *   <li>{@link com.aipersimmon.ddd.tenancy.spring.HeaderTenantResolver} — the default resolver,
 *       reachable only after affirming {@code aipersimmon.ddd.tenancy.trust-header=true}. A
 *       client-supplied header tied to no authenticated principal is not adopted as a default; see
 *       {@link com.aipersimmon.ddd.tenancy.spring.UntrustedTenantHeaderException}.
 * </ul>
 *
 * <p>The whole resolution activates only when {@code aipersimmon.ddd.tenancy.enabled=true}; a
 * single-tenant deployment behaves exactly as it did before tenancy existed (commands run under the
 * {@code __root__} sentinel seeded by the command bus). Once enabled, isolation fails closed: the
 * sentinel stops being a fallback and an unbound thread reaching tenant-scoped data raises {@link
 * com.aipersimmon.ddd.tenancy.MissingTenantException}.
 */
package com.aipersimmon.ddd.tenancy.spring;

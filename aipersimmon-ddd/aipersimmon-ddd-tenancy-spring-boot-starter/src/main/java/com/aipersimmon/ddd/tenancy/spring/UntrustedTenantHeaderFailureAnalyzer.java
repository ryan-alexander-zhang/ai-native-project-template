package com.aipersimmon.ddd.tenancy.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns the refusal to trust a client-supplied tenant header into an actionable message naming both
 * safe arrangements, so a first-time consumer sees the decision they have to make rather than a
 * bare bean-creation failure.
 *
 * <p>Registered via {@code META-INF/spring.factories}.
 */
public class UntrustedTenantHeaderFailureAnalyzer
    extends AbstractFailureAnalyzer<UntrustedTenantHeaderException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, UntrustedTenantHeaderException cause) {
    String description =
        "Multi-tenancy is enabled (aipersimmon.ddd.tenancy.enabled=true) but no TenantResolver bean "
            + "is defined, so the tenant would be read from a request header the caller controls. "
            + "Nothing ties that header to an authenticated principal: any caller able to reach the "
            + "service could read and write any tenant's data by changing it. The framework will not "
            + "adopt that as a default.";
    String action =
        "Pick the arrangement that matches your deployment.\n\n"
            + "1. Resolve the tenant from the authenticated principal (no trusted edge needed):\n\n"
            + "    @Bean\n"
            + "    TenantResolver tenantResolver() {\n"
            + "      // read from your SecurityContext / verified token claim, never from a header\n"
            + "      return context -> currentPrincipal().map(p -> Tenants.of(p.tenantId()));\n"
            + "    }\n\n"
            + "2. Keep the header, because a gateway, service mesh, or BFF in front of this service "
            + "authenticates the caller and rewrites the header itself, discarding whatever the "
            + "client sent:\n\n"
            + "    aipersimmon.ddd.tenancy.trust-header: true\n\n"
            + "Option 2 is only safe if that component cannot be bypassed — if the service is "
            + "reachable directly (in-cluster traffic, a port-forward, a misrouted ingress), the "
            + "header is spoofable again and option 1 is the correct choice.";
    return new FailureAnalysis(description, action, cause);
  }
}

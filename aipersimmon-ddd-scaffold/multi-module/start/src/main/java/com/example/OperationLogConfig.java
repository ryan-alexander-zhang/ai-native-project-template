package com.example;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationTenantResolver;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the two trusted resolvers the Operation Log capture layer requires. The capture
 * interceptors call these — never the command payload — to stamp the actor and tenant onto each
 * recorded row, so both must resolve from a trusted scope. The cqrs-spring auto-configuration wires
 * the interceptors only when a storage backend is present and fails fast at startup if either
 * resolver is missing, which is why they live here in the composition root.
 *
 * <p>This reference app has no security context, so the actor is a constant system actor. The
 * tenant, however, is real: with multi-tenancy enabled (design-00009) the tenant is bound into the
 * {@link TenantContext} at the trusted boundary — the web edge filter, or the command bus for a
 * relayed/scheduled dispatch — and this resolver simply reads it back, falling to the {@code
 * __root__} sentinel for any un-tenanted (single-tenant N=1) path. It reads from {@code
 * TenantContext}, never the command payload, exactly because the payload is untrusted.
 */
@Configuration(proxyBeanMethods = false)
public class OperationLogConfig {

  /**
   * No authenticated principal in this scaffold, so every write is attributed to one system actor.
   */
  @Bean
  OperationActorResolver operationActorResolver() {
    return () -> Actor.system("ordering-scaffold");
  }

  /**
   * The tenant stamped on each audit row is the one bound to the current {@link TenantContext} (the
   * trusted boundary), or the {@code __root__} sentinel when none is bound.
   */
  @Bean
  OperationTenantResolver operationTenantResolver() {
    return () -> TenantContext.current().map(TenantId::value).orElse(Tenants.ROOT.value());
  }
}

package com.example;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationTenantResolver;
import com.aipersimmon.ddd.operationlog.model.Actor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the two trusted resolvers the Operation Log capture layer requires. The capture
 * interceptors call these — never the command payload — to stamp the actor and tenant onto each
 * recorded row, so both must resolve from a trusted scope. The cqrs-spring auto-configuration wires
 * the interceptors only when a storage backend is present and fails fast at startup if either
 * resolver is missing, which is why they live here in the composition root.
 *
 * <p>This reference app has no security context and is single-tenant, so the defaults are trivial:
 * a constant system actor and the {@code GLOBAL} tenant. A real application resolves the caller
 * from its {@code SecurityContext} (or request/invocation scope) and the tenant from the same
 * trusted boundary.
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

  /** Single-tenant scaffold (multi-tenancy disabled): the tenant is the GLOBAL normalization. */
  @Bean
  OperationTenantResolver operationTenantResolver() {
    return () -> "GLOBAL";
  }
}

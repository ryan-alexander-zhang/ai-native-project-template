package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import com.aipersimmon.ddd.tenancy.TenantContext;

/**
 * Binds the ambient {@link TenantContext} from the command's {@link CommandContext#tenantId()} for
 * the whole handling. The command bus already seeds {@code CommandContext} <em>from</em> the
 * ambient tenant at the edge; this is the reverse guarantee for dispatches that have no ambient
 * tenant on their thread — a durable effect relay, a scheduler, a batch job — so the read side and
 * infrastructure they touch still see the command's tenant.
 *
 * <p>Ordered well outside the chain so the whole handling (and every inner interceptor) runs with
 * the tenant bound.
 */
public class TenantContextCommandInterceptor implements CommandInterceptor {

  /** Outermost-but-one (just inside tracing), so the tenant is bound around the whole chain. */
  public static final int ORDER = -90;

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
    // The context carries a real TenantId, so binding needs no re-parse: whoever minted the
    // context already performed the trust-boundary act.
    return TenantContext.runAs(context.tenantId(), invocation::proceed);
  }
}

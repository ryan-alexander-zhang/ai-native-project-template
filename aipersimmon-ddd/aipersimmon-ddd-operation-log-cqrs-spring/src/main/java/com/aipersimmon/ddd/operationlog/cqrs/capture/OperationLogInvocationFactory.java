package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import java.time.Clock;
import java.util.Objects;

/**
 * Builds an immutable {@link OperationLogInvocation} from the dispatch {@link CommandContext} and
 * the trusted resolvers. Each interceptor calls it to freeze its own snapshot; the resolvers are
 * stateless so the two paths never share mutable state.
 */
public final class OperationLogInvocationFactory {

  private final String source;
  private final Clock clock;
  private final OperationActorResolver actorResolver;
  private final OperationTenantResolver tenantResolver;

  public OperationLogInvocationFactory(
      String source,
      Clock clock,
      OperationActorResolver actorResolver,
      OperationTenantResolver tenantResolver) {
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
    this.tenantResolver = Objects.requireNonNull(tenantResolver, "tenantResolver");
  }

  /** Freeze an invocation for the given dispatch context. */
  public OperationLogInvocation create(CommandContext context) {
    return OperationLogInvocation.builder()
        .source(source)
        .tenant(tenantResolver.resolve())
        .actor(actorResolver.resolve())
        .causality(
            new Causality(context.messageId(), context.correlationId(), context.causationId()))
        .occurredAt(clock.instant())
        .build();
  }
}

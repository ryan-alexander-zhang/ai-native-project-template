package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.time.Instant;
import java.util.Objects;

/**
 * The trusted, immutable context of a single record attempt: the logical {@code source}, the {@code
 * tenantId}, the {@link Actor}, the {@link Causality}, and when the operation occurred. It is a
 * typed value, not an arbitrary key/value context. A CQRS adapter builds it from the dispatch
 * context and resolvers; a direct-API caller builds it explicitly at a trusted boundary.
 *
 * <p>Lives in {@code model} (not {@code definition}) so the immutable model has no dependency on
 * the definition lifecycle, keeping the packages acyclic.
 */
@ValueObject
public record OperationLogInvocation(
    String source, String tenantId, Actor actor, Causality causality, Instant occurredAt) {

  /**
   * @throws NullPointerException if source, tenantId, actor, or occurredAt is null
   */
  public OperationLogInvocation {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(occurredAt, "occurredAt");
    causality = causality == null ? Causality.none() : causality;
  }

  /** A builder seeded with the required trusted fields set later. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link OperationLogInvocation}. */
  public static final class Builder {
    private String source;
    private String tenantId = "GLOBAL";
    private Actor actor;
    private Causality causality = Causality.none();
    private Instant occurredAt;

    private Builder() {}

    /** The stable logical producer identity. */
    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /** The trusted tenant; defaults to {@code GLOBAL} for non-multi-tenant callers. */
    public Builder tenant(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    /** The captured actor snapshot. */
    public Builder actor(Actor actor) {
      this.actor = actor;
      return this;
    }

    /** The causal triple, if any. */
    public Builder causality(Causality causality) {
      this.causality = causality;
      return this;
    }

    /** When the operation occurred (UTC). */
    public Builder occurredAt(Instant occurredAt) {
      this.occurredAt = occurredAt;
      return this;
    }

    /** Build the immutable invocation. */
    public OperationLogInvocation build() {
      return new OperationLogInvocation(source, tenantId, actor, causality, occurredAt);
    }
  }
}

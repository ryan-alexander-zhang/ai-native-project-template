package com.example.samples.s06.risk.application;

import com.aipersimmon.ddd.cqrs.Query;
import com.example.samples.s06.risk.domain.RiskDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Assess an order's risk.
 *
 * <p>A {@link Query}, not a {@code Command}, because it changes nothing — and that classification is
 * load-bearing for the caller, not just tidy here. A query is safe to retry, safe to time out and retry
 * again, and safe to call twice while a request is in flight. The moment this became a command that
 * recorded the assessment, the caller's retry would need an idempotency key and its timeout would become
 * an unknown outcome rather than a repeatable question.
 *
 * <p>The constraints are validated by the controller, not by the query side: the framework ships
 * <em>no</em> query interceptors, so nothing between the bus and the handler validates or wraps
 * anything. On the read side the contract is the query type's own business (S20 argues this at length).
 */
public record AssessRisk(@NotBlank String customerId, @Positive long amountCents)
    implements Query<RiskDecision> {}

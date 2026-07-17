package com.aipersimmon.ddd.processmanager.jdbc.store;

import java.time.Instant;
import java.util.Optional;

/**
 * One entry in an instance's transition timeline: the lifecycle/step move, the
 * decision code, the transition kind, and — for operator actions — the operator and reason. The
 * input payload is deliberately omitted; the timeline is metadata, not decoded business state.
 */
public record ProcessTransitionView(
        String transitionId,
        String inputMessageId,
        Optional<String> fromLifecycle,
        String toLifecycle,
        Optional<String> fromStep,
        String toStep,
        String decisionCode,
        String transitionKind,
        Optional<String> operator,
        Optional<String> reason,
        Instant createdAt) {
}

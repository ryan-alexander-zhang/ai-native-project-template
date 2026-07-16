package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;

/**
 * The controlled extension point for delivering one kind of effect. The relay hands it a
 * decoded effect and the reconstructed dispatch context; the dispatcher performs the
 * outward action (send a command, publish an event). Exactly one dispatcher is registered
 * per configured {@link ProcessEffectKind}.
 */
public interface ProcessEffectDispatcher {

    /** The effect kind this dispatcher handles. */
    ProcessEffectKind kind();

    /**
     * Deliver the effect. Any exception is treated by the relay as a delivery failure and
     * drives bounded retry; delivery is at-least-once, so the action must be idempotent.
     */
    void dispatch(DecodedProcessEffect effect, CommandContext context);
}

package com.aipersimmon.ddd.processmanager.effect;

/**
 * A side effect a {@link com.aipersimmon.ddd.processmanager.definition.ProcessDecision}
 * asks the runtime to perform once the transition commits — never inside the advance
 * transaction. An effect carries business payload only: it holds no messageId,
 * correlation, causation, or trace. The durable runtime mints and persists the
 * effect's identity when it stages the effect, and replays it verbatim on delivery
 * (design-00004 §3.5).
 *
 * <p>A sealed set so a runtime handles every kind exhaustively.
 */
public sealed interface ProcessEffect
        permits DispatchCommand,
                PublishIntegrationEvent,
                ScheduleDeadline,
                CancelDeadline {

    /** The kind of this effect, for dispatcher routing. */
    ProcessEffectKind kind();
}

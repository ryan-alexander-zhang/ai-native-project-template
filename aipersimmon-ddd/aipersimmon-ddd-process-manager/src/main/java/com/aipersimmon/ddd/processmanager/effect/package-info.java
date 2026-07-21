/**
 * The sealed set of side effects a process decision can request: send a command ({@link
 * com.aipersimmon.ddd.processmanager.effect.DispatchCommand}), publish an integration event ({@link
 * com.aipersimmon.ddd.processmanager.effect.PublishIntegrationEvent}), or arm/cancel a named timer
 * ({@link com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline} / {@link
 * com.aipersimmon.ddd.processmanager.effect.CancelDeadline}).
 *
 * <p>Effects carry business payload only — no transport metadata. The durable runtime mints and
 * persists each effect's message identity when it stages it, then replays it verbatim
 * at-least-once; downstream handlers must be idempotent. Command effects are in-process;
 * cross-service coordination uses integration-event effects.
 */
package com.aipersimmon.ddd.processmanager.effect;

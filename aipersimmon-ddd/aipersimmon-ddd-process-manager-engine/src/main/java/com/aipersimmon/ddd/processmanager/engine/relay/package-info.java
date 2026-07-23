/**
 * The effect relay that delivers staged effects at-least-once, outside the advance transaction.
 * {@link com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectRelay} claims due,
 * per-instance-ordered effects, decodes each into a {@link
 * com.aipersimmon.ddd.processmanager.engine.relay.DecodedProcessEffect}, and routes it through the
 * {@link com.aipersimmon.ddd.processmanager.engine.relay.EffectDispatcherRegistry} to the {@link
 * com.aipersimmon.ddd.processmanager.engine.relay.ProcessEffectDispatcher} for its kind — {@link
 * com.aipersimmon.ddd.processmanager.engine.relay.CommandEffectDispatcher} (in-process command) or
 * {@link com.aipersimmon.ddd.processmanager.engine.relay.IntegrationEventEffectDispatcher}
 * (cross-service event). Completion is fenced by the lease token; exhausted retries move the effect
 * to DEAD and suspend the instance.
 */
package com.aipersimmon.ddd.processmanager.engine.relay;

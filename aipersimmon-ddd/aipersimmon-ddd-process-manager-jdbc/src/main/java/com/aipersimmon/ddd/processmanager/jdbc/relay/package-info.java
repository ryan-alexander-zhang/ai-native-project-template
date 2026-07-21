/**
 * The effect relay that delivers staged effects at-least-once, outside the advance transaction.
 * {@link com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay} claims due,
 * per-instance-ordered effects, decodes each into a {@link
 * com.aipersimmon.ddd.processmanager.jdbc.relay.DecodedProcessEffect}, and routes it through the
 * {@link com.aipersimmon.ddd.processmanager.jdbc.relay.EffectDispatcherRegistry} to the {@link
 * com.aipersimmon.ddd.processmanager.jdbc.relay.ProcessEffectDispatcher} for its kind — {@link
 * com.aipersimmon.ddd.processmanager.jdbc.relay.CommandEffectDispatcher} (in-process command) or
 * {@link com.aipersimmon.ddd.processmanager.jdbc.relay.IntegrationEventEffectDispatcher}
 * (cross-service event). Completion is fenced by the lease token; exhausted retries move the effect
 * to DEAD and suspend the instance.
 */
package com.aipersimmon.ddd.processmanager.jdbc.relay;

/**
 * The runtime ports a durable process is driven and inspected through:
 * {@link com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime} (command side:
 * {@code start} / {@code handle}, returning a
 * {@link com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult}) and
 * {@link com.aipersimmon.ddd.processmanager.runtime.ProcessQuery} (read side, returning
 * a {@link com.aipersimmon.ddd.processmanager.runtime.ProcessView}).
 *
 * <p>These are the contracts of a local durable Process Manager runtime — not a unified
 * engine interface Temporal or Seata must implement. Neither port exposes an arbitrary
 * state-mutation API; state changes only through a definition's decision.
 */
package com.aipersimmon.ddd.processmanager.runtime;

/**
 * Runtime identity and lifecycle value objects for a durable process, kept separate
 * from the consumer's business state.
 *
 * <p>{@link com.aipersimmon.ddd.processmanager.model.ProcessRef} (instance id + type +
 * business key) identifies an instance; {@link com.aipersimmon.ddd.processmanager.model.ProcessRevision}
 * is its optimistic version; {@link com.aipersimmon.ddd.processmanager.model.ProcessLifecycle}
 * is the runtime state machine; {@link com.aipersimmon.ddd.processmanager.model.ProcessStep}
 * and {@link com.aipersimmon.ddd.processmanager.model.ProcessOutcome} are stable
 * consumer-defined strings for business progress and terminal result. Every value
 * object is an immutable record that validates its own arguments.
 */
package com.aipersimmon.ddd.processmanager.model;

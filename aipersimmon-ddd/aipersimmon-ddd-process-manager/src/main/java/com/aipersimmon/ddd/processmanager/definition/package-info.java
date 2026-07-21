/**
 * The Definition/Decision model a consumer implements for a durable process.
 *
 * <p>A {@link com.aipersimmon.ddd.processmanager.definition.ProcessDefinition} is a pure,
 * deterministic decision object: given a state, a {@link
 * com.aipersimmon.ddd.processmanager.definition.ProcessInput}, and a read-only {@link
 * com.aipersimmon.ddd.processmanager.definition.ProcessContext}, it returns a {@link
 * com.aipersimmon.ddd.processmanager.definition.ProcessDecision} (new state, target lifecycle/step,
 * optional outcome, and ordered effects). It does no I/O, so it is unit-testable and replayable.
 * The {@link com.aipersimmon.ddd.processmanager.definition.ProcessDefinitionRegistry} indexes
 * registered definitions and enforces one active version per type at startup.
 */
package com.aipersimmon.ddd.processmanager.definition;

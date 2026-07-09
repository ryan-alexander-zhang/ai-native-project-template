/**
 * Integration-tier building blocks: {@link com.aipersimmon.ddd.integration.IntegrationEvent},
 * the marker for a cross-context event that is part of a bounded context's
 * published language, and {@link com.aipersimmon.ddd.integration.EventEnvelope},
 * which carries the metadata a transport needs around a typed payload.
 *
 * <p>An integration event is a versioned contract: once published it must evolve
 * backward-compatibly (add optional fields; do not remove or repurpose existing
 * ones), and a breaking change is a new {@code version}. This is distinct from an
 * internal domain event, which a context may change freely.
 */
package com.aipersimmon.ddd.integration;

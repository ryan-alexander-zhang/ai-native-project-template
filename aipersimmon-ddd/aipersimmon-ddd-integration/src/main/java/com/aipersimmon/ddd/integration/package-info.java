/**
 * Integration-tier building blocks: {@link com.aipersimmon.ddd.integration.IntegrationEvent}, the
 * marker for a cross-context event that is part of a bounded context's published language, and
 * {@link com.aipersimmon.ddd.integration.EventEnvelope}, which carries the metadata a transport
 * needs around a typed payload.
 *
 * <p>An integration event is a versioned contract: once published it must evolve
 * backward-compatibly (add optional fields; do not remove or repurpose existing ones), and a
 * breaking change is a new {@code version}. This is distinct from an internal domain event, which a
 * context may change freely.
 *
 * <p>Its logical identity is declared with {@link com.aipersimmon.ddd.integration.EventType} and
 * its transport <em>reach</em> with {@link com.aipersimmon.ddd.integration.Externalized}: an event
 * is LOCAL (in-process only) by default and EXTERNAL (routed to a named broker target) only when
 * explicitly annotated. Keeping these two as separate annotations holds the line between contract
 * identity and deployment concern.
 */
package com.aipersimmon.ddd.integration;

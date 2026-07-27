package com.aipersimmon.ddd.outbox.spring.fixtures.external;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * An event the application has declared part of another process's diet. In its own package so a
 * test can scan for "an application with something externalized" without dragging in unrelated
 * fixtures.
 */
@EventType(name = "com.example.ExternalEvent", version = 1)
@Externalized("example.events")
public record ExternalEvent(String id) implements IntegrationEvent {}

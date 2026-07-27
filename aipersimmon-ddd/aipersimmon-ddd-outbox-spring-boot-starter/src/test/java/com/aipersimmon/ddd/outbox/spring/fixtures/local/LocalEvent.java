package com.aipersimmon.ddd.outbox.spring.fixtures.local;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * An event with no {@code @Externalized}: LOCAL, the default reach. In its own package so a test
 * can scan for "an application with nothing externalized".
 */
@EventType(name = "com.example.LocalEvent", version = 1)
public record LocalEvent(String id) implements IntegrationEvent {}

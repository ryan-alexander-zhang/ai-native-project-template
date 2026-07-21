package com.aipersimmon.ddd.messaging.kafka.wiringfixture;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Fixture: an EXTERNAL event, so the wiring test's scan finds an externalized route. */
@EventType(name = "com.example.wiring.Externalized", version = 1)
@Externalized("wiring.events")
public record ExternalizedFixtureEvent(String id) implements IntegrationEvent {

    @Override
    public String subject() {
        return id;
    }
}

package com.aipersimmon.ddd.events.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SpringIntegrationEventsTest {

    record SampleIntegrationEvent(String id) implements IntegrationEvent {
    }

    @Test
    void publishDelegatesToApplicationEventPublisher() {
        List<Object> captured = new ArrayList<>();
        ApplicationEventPublisher publisher = captured::add;
        IntegrationEvents events = new SpringIntegrationEvents(publisher);

        SampleIntegrationEvent event = new SampleIntegrationEvent("1");
        events.publish(event);

        assertEquals(List.of(event), captured);
    }
}

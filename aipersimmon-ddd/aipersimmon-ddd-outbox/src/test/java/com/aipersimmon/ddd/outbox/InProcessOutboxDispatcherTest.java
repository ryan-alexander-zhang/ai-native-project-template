package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-tests the in-process dispatcher's reconstruct-and-republish behavior without
 * a Spring context: it rebuilds the event from the stored type + payload and hands
 * it to the publisher, and fails clearly on an unknown type. The full wiring
 * (writer -> relay -> dispatcher) is covered by each storage starter's own test.
 */
class InProcessOutboxDispatcherTest {

    record SampleEvent(String orderId) {
    }

    @Test
    void reconstructsStoredEventAndPublishesIt() {
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        InProcessOutboxDispatcher dispatcher =
                new InProcessOutboxDispatcher(publisher, new ObjectMapper());

        dispatcher.dispatch(new OutboxMessage(
                "evt-1", SampleEvent.class.getName(), 1, "{\"orderId\":\"O-1\"}",
                Instant.EPOCH, null));

        assertEquals(1, published.size());
        SampleEvent event = assertInstanceOf(SampleEvent.class, published.get(0));
        assertEquals("O-1", event.orderId());
    }

    @Test
    void failsClearlyWhenTypeIsUnknown() {
        InProcessOutboxDispatcher dispatcher =
                new InProcessOutboxDispatcher(event -> { }, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(new OutboxMessage(
                "evt-2", "com.example.DoesNotExist", 1, "{}", Instant.EPOCH, null)));
    }
}

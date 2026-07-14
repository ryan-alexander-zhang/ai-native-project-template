package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventTypeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Unit-tests the in-process dispatcher's reconstruct-and-republish behavior without
 * a Spring context: it rebuilds the {@link EventEnvelope} from the stored metadata +
 * payload and hands it to the publisher, and fails clearly on an unknown type. The
 * full wiring (writer -> relay -> dispatcher) is covered by each storage starter's
 * own test.
 */
class InProcessOutboxDispatcherTest {

    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    // Empty registry → resolves by the FQCN fallback (shared-classpath case).
    private final InProcessOutboxDispatcher dispatcher(ApplicationEventPublisher publisher) {
        return new InProcessOutboxDispatcher(
                publisher, new ObjectMapper(), new RegistryIntegrationEventTypeResolver(Map.of()));
    }

    @Test
    void reconstructsStoredEventAndPublishesTheEnvelope() {
        List<Object> published = new ArrayList<>();

        dispatcher(published::add).dispatch(new OutboxMessage(
                "evt-1", "/orders", SampleEvent.class.getName(), 1, "{\"orderId\":\"O-1\"}",
                Instant.EPOCH, "O-1", "corr-1", "cause-1", null));

        assertEquals(1, published.size());
        PayloadApplicationEvent<?> event = assertInstanceOf(PayloadApplicationEvent.class, published.get(0));
        EventEnvelope<?> envelope = assertInstanceOf(EventEnvelope.class, event.getPayload());
        SampleEvent payload = assertInstanceOf(SampleEvent.class, envelope.payload());
        assertEquals("O-1", payload.orderId());
        assertEquals("/orders", envelope.source());
        assertEquals("O-1", envelope.subject());
        assertEquals("corr-1", envelope.correlationId());
        assertEquals("cause-1", envelope.causationId());
    }

    @Test
    void failsClearlyWhenTypeIsUnknown() {
        assertThrows(IllegalStateException.class, () -> dispatcher(event -> { }).dispatch(new OutboxMessage(
                "evt-2", "/orders", "com.example.DoesNotExist", 1, "{}", Instant.EPOCH, null, "corr-1", null, null)));
    }
}

package com.example.samples.s21;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s21.inventory.api.OrderPlaced;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * A second listener for the same envelope, recording what the application was actually handed.
 *
 * <p>It exists because the interesting claims about an upcast are about the <em>payload object</em>, not
 * about the stock: that the new field is absent rather than filled in, that the envelope's version
 * describes the payload delivered, and that identity is still the wire's. None of that is visible from
 * the database.
 */
@TestConfiguration(proxyBeanMethods = false)
class ReceivedEnvelopes {

  static class Recorder {
    private final List<EventEnvelope<OrderPlaced>> received = new CopyOnWriteArrayList<>();

    @EventListener
    void on(EventEnvelope<OrderPlaced> envelope) {
      received.add(envelope);
    }

    List<EventEnvelope<OrderPlaced>> forOrder(String orderId) {
      return received.stream().filter(e -> orderId.equals(e.payload().orderId())).toList();
    }

    void clear() {
      received.clear();
    }
  }

  @Bean
  Recorder receivedEnvelopeRecorder() {
    return new Recorder();
  }
}

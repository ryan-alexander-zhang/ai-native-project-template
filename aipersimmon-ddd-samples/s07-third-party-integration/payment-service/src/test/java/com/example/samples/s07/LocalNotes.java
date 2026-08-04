package com.example.samples.s07;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * A LOCAL integration event and something that listens for it, so the in-process leg of this
 * application's composed dispatcher can be proved to exist.
 *
 * <p>The production code has no local events — every event it publishes goes to the provider — which is
 * exactly the situation in which the missing leg would never be noticed. Defining this application's own
 * {@code OutboxDispatcher} makes the library's in-process republisher back off
 * ({@code @ConditionalOnMissingBean}), and because the relay marks a row sent whenever dispatch returns
 * normally, an event with no destination would be archived as delivered with nothing to notice: no
 * exception, no dead letter, no lag. The fixture is here so that "the leg is wired" is an assertion.
 *
 * <p>Note the absence of {@code @Externalized}: that annotation, and nothing else, is what makes an event
 * leave the process.
 */
@TestConfiguration(proxyBeanMethods = false)
class LocalNotes {

  /** A fact that stays at home. */
  @EventType(name = "com.example.samples.payments.NoteRecorded", version = 1, source = "/payments")
  record NoteRecorded(String paymentId, String note) implements IntegrationEvent {

    @Override
    public String subject() {
      return paymentId;
    }
  }

  static class Recorder {

    private final List<EventEnvelope<NoteRecorded>> received = new CopyOnWriteArrayList<>();

    @EventListener
    void on(EventEnvelope<NoteRecorded> envelope) {
      received.add(envelope);
    }

    List<EventEnvelope<NoteRecorded>> notesFor(String paymentId) {
      return received.stream().filter(e -> paymentId.equals(e.payload().paymentId())).toList();
    }

    void clear() {
      received.clear();
    }
  }

  @Bean
  Recorder localNoteRecorder() {
    return new Recorder();
  }
}

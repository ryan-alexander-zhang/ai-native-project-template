package com.example.samples.s03;

import com.example.samples.s03.ordering.application.CustomerNotifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * One test double for every test class in this sample, so they all share a single Spring context — and
 * therefore a single container. A per-class {@code @TestConfiguration} would start another.
 */
@TestConfiguration(proxyBeanMethods = false)
class RecordingNotifier {

  @Bean
  @Primary
  Notifier recordingNotifier() {
    return new Notifier();
  }

  /** Records what it was asked to send, and refuses for one customer so a failure can be observed. */
  static final class Notifier implements CustomerNotifier {

    private final List<String> notified = new CopyOnWriteArrayList<>();

    @Override
    public void orderConfirmedTo(String customerId, String orderId) {
      if (customerId.startsWith("unreachable")) {
        throw new IllegalStateException("the notification provider is down");
      }
      notified.add(customerId + ":" + orderId);
    }

    List<String> notified() {
      return List.copyOf(notified);
    }

    void reset() {
      notified.clear();
    }
  }
}

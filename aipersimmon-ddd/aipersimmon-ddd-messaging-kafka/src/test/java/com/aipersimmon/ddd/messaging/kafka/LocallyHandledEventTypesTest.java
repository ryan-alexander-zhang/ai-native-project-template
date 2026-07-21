package com.aipersimmon.ddd.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.event.EventListener;

/**
 * The startup scan narrows the "locally handled" set from unambiguous
 * {@code @EventListener(EventEnvelope<ConcreteEvent>)} methods, and falls back to handle-everything
 * (never skip) whenever a listener could match more than one type. Uses a bare {@link
 * DefaultListableBeanFactory} so only the registered beans are scanned (no framework beans with
 * their own {@code @EventListener}s to perturb the result).
 */
class LocallyHandledEventTypesTest {

  private static DefaultListableBeanFactory beanFactoryWith(Class<?>... beanClasses) {
    DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
    int i = 0;
    for (Class<?> type : beanClasses) {
      bf.registerBeanDefinition("bean" + i++, new RootBeanDefinition(type));
    }
    return bf;
  }

  @Test
  void narrowsToTheConcreteTypesWithAnEventListener() {
    LocallyHandledEventTypes handled =
        LocallyHandledEventTypes.scan(beanFactoryWith(AlphaHandler.class));

    assertThat(handled.handlesAll()).isFalse();
    assertThat(handled.handles("test.Alpha", 1)).isTrue();
    assertThat(handled.handles("test.Beta", 1)).isFalse();
    assertThat(handled.handles("test.Alpha", 2)).isFalse();
  }

  @Test
  void aWildcardEnvelopeListenerForcesHandleEverything() {
    LocallyHandledEventTypes handled =
        LocallyHandledEventTypes.scan(beanFactoryWith(AlphaHandler.class, WildcardHandler.class));

    assertThat(handled.handlesAll()).isTrue();
    assertThat(handled.handles("anything.at.all", 9)).isTrue();
  }

  @Test
  void listenersForUnrelatedEventsAndClassesDeclarationsDoNotWiden() {
    LocallyHandledEventTypes handled =
        LocallyHandledEventTypes.scan(
            beanFactoryWith(AlphaHandler.class, UnrelatedHandler.class, ClassesHandler.class));

    assertThat(handled.handlesAll()).isFalse();
    assertThat(handled.handles("test.Alpha", 1)).isTrue();
    assertThat(handled.handles("test.Beta", 1)).isFalse();
  }

  @Test
  void nothingHandledWhenNoListeners() {
    LocallyHandledEventTypes handled =
        LocallyHandledEventTypes.scan(beanFactoryWith(PlainBean.class));

    assertThat(handled.handlesAll()).isFalse();
    assertThat(handled.handles("test.Alpha", 1)).isFalse();
  }

  // --- fixtures ----------------------------------------------------------

  @EventType(name = "test.Alpha", version = 1)
  record AlphaEvent(String value) implements IntegrationEvent {}

  @EventType(name = "test.Beta", version = 1)
  record BetaEvent(String value) implements IntegrationEvent {}

  static class AlphaHandler {
    @EventListener
    void on(EventEnvelope<AlphaEvent> event) {}
  }

  static class WildcardHandler {
    @EventListener
    void on(EventEnvelope<?> event) {}
  }

  /** Listens to a non-envelope event: must not affect the integration-event set. */
  static class UnrelatedHandler {
    @EventListener
    void on(String somethingElse) {}
  }

  /** Declares its event type via classes() and takes no envelope param: unrelated, ignored. */
  static class ClassesHandler {
    @EventListener(String.class)
    void on() {}
  }

  static class PlainBean {
    void notAListener() {}
  }
}

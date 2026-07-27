package com.aipersimmon.ddd.outbox.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.LoggingOutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which dispatcher gets wired, and — the point of this class — that an application whose events
 * were meant to leave the process cannot start with one that has nowhere to send them.
 *
 * <p>The failure this guards against is invisible by construction. The relay marks a row sent
 * whenever {@code dispatch} returns normally, so a dispatcher that quietly drops everything
 * produces no exception, no dead letter, no retry and no consumer lag. A deployment that forgot its
 * messaging starter would archive every integration event as delivered and look perfectly healthy
 * while the downstream heard nothing. Startup is the only moment this is still cheap to notice.
 */
class DispatcherSelectionTest {

  private static final String EXTERNAL_EVENTS =
      "aipersimmon.ddd.integration.scan-packages=com.aipersimmon.ddd.outbox.spring.fixtures.external";
  private static final String LOCAL_EVENTS_ONLY =
      "aipersimmon.ddd.integration.scan-packages=com.aipersimmon.ddd.outbox.spring.fixtures.local";
  private static final String ALLOW_LOSS =
      "aipersimmon.ddd.outbox.allow-unreachable-external-events=true";
  private static final String GUARD_BEAN = "aipersimmonDddExternalReachGuard";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddOutboxAutoConfiguration.class));

  @Test
  void theDefaultDeliversInProcessRatherThanDiscarding() {
    runner
        .withPropertyValues(LOCAL_EVENTS_ONLY)
        .run(
            context -> {
              assertInstanceOf(
                  InProcessOutboxDispatcher.class,
                  context.getBean(OutboxDispatcher.class),
                  "with no transport configured the default must still deliver LOCAL events, "
                      + "not log them and mark them sent");
              assertFalse(
                  context.getBean(OutboxDispatcher.class).reachesExternalTargets(),
                  "in-process delivery stops at the JVM boundary and must say so");
            });
  }

  @Test
  void loggingIsReachableOnlyByAskingForIt() {
    runner
        .withPropertyValues(LOCAL_EVENTS_ONLY, "aipersimmon.ddd.outbox.dispatch=logging")
        .run(
            context ->
                assertInstanceOf(
                    LoggingOutboxDispatcher.class, context.getBean(OutboxDispatcher.class)));
  }

  @Test
  void anUnrecognisedDispatchModeIsRejectedRatherThanMatchingNothing() {
    // 'kafka' is the plausible wrong guess: the transport is chosen by adding a starter, not here.
    // Left unvalidated, @ConditionalOnProperty would match no bean at all and the failure would
    // surface much later as an unsatisfied dependency of the relay.
    runner
        .withPropertyValues(LOCAL_EVENTS_ONLY, "aipersimmon.ddd.outbox.dispatch=kafka")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure(), "a typo must not be accepted");
              assertTrue(
                  rootMessage(context.getStartupFailure())
                      .contains("aipersimmon.ddd.outbox.dispatch"),
                  "the message must name the property that is wrong");
            });
  }

  @Test
  void externalizedEventsWithNoWayOutFailStartup() {
    runner
        .withPropertyValues(EXTERNAL_EVENTS)
        .run(
            context -> {
              assertNotNull(
                  context.getStartupFailure(),
                  "@Externalized events plus an in-process-only dispatcher is silent data loss and "
                      + "must not start");
              String message = rootMessage(context.getStartupFailure());
              assertTrue(
                  message.contains("@Externalized"), "the message must say what triggered it");
              assertTrue(
                  message.contains(InProcessOutboxDispatcher.class.getName()),
                  "the message must name the dispatcher that cannot reach the target");
              assertTrue(
                  message.contains("aipersimmon-ddd-messaging-kafka"),
                  "the message must point at the fix, not just the fault");
            });
  }

  @Test
  void loggingIsAlsoRefusedWhenSomethingIsExternalized() {
    // Asking for `logging` is not consent to lose events that were declared external: it is the
    // dispatch mode that is opt-in, not the loss.
    runner
        .withPropertyValues(EXTERNAL_EVENTS, "aipersimmon.ddd.outbox.dispatch=logging")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void aTransportThatReachesExternalTargetsIsAccepted() {
    runner
        .withPropertyValues(EXTERNAL_EVENTS)
        .withUserConfiguration(RealTransport.class)
        .run(
            context -> {
              assertNull(
                  context.getStartupFailure(),
                  "a dispatcher that can deliver externally must not be accused of anything");
              assertInstanceOf(ReachingDispatcher.class, context.getBean(OutboxDispatcher.class));
            });
  }

  @Test
  void nothingExternalizedMeansNothingToGuard() {
    runner
        .withPropertyValues(LOCAL_EVENTS_ONLY)
        .run(
            context ->
                assertNull(
                    context.getStartupFailure(),
                    "an application that externalizes nothing is completely served by in-process "
                        + "delivery and must not be second-guessed"));
  }

  @Test
  void theLossCanBeAcceptedDeliberately() {
    runner
        .withPropertyValues(EXTERNAL_EVENTS, ALLOW_LOSS)
        .run(
            context -> {
              assertNull(
                  context.getStartupFailure(),
                  "an operator who has said they accept the loss (a local run without a broker) "
                      + "must not be blocked");
              assertInstanceOf(
                  InProcessOutboxDispatcher.class, context.getBean(OutboxDispatcher.class));
            });
  }

  @Test
  void theGuardIsRegisteredOnlyWhenItHasSomethingToSay() {
    runner
        .withPropertyValues(LOCAL_EVENTS_ONLY)
        .run(
            context ->
                assertFalse(
                    context.containsBean(GUARD_BEAN),
                    "with nothing externalized the guard has no question to ask and should not be "
                        + "wired at all"));

    runner
        .withPropertyValues(EXTERNAL_EVENTS, ALLOW_LOSS)
        .run(
            context ->
                assertTrue(
                    context.containsBean(GUARD_BEAN),
                    "something externalized means the guard is the thing deciding, so it must be "
                        + "present even when it has been told to permit the loss"));
  }

  /** Walks to the root cause: a bean-creation failure wraps the message this test cares about. */
  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    StringBuilder all = new StringBuilder();
    while (current != null) {
      all.append(current.getMessage()).append('\n');
      current = current.getCause();
    }
    return all.toString();
  }

  @Configuration(proxyBeanMethods = false)
  static class RealTransport {
    @Bean
    OutboxDispatcher reachingDispatcher() {
      return new ReachingDispatcher();
    }
  }

  /**
   * Stands in for a messaging starter's dispatcher. It overrides nothing: {@code
   * reachesExternalTargets()} defaults to {@code true}, so a custom transport is trusted rather
   * than required to prove itself — only the framework's own process-bound dispatchers opt out.
   */
  static final class ReachingDispatcher implements OutboxDispatcher {
    @Override
    public void dispatch(OutboxMessage message) {}
  }
}

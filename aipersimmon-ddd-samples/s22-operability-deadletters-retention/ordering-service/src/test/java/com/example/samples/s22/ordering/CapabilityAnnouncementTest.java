package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A framework that quietly does less than you asked is the worst kind. This class measures what this one
 * says instead, and the answers are not uniform — which is the finding, not a complaint.
 *
 * <table>
 *   <caption>Measured below</caption>
 *   <tr><th>Missing capability</th><th>What happens</th></tr>
 *   <tr><td>{@code @Externalized} events and no transport that reaches outside</td>
 *       <td>startup <strong>fails</strong>; overridable by one property</td></tr>
 *   <tr><td>the same, with the override set</td><td>starts, with a WARN naming the loss</td></tr>
 *   <tr><td>an edge guard running on the in-memory store</td>
 *       <td>starts, with a WARN; <strong>fails</strong> only if asked to be strict</td></tr>
 * </table>
 *
 * <p>The two postures differ because the losses differ. Publishing into a dead end loses <em>facts</em>
 * that no later run can recover, and nothing observable changes — no exception, no dead letter, no
 * consumer lag — so the only moment anyone can be told is startup. An in-memory idempotency store, by
 * contrast, works correctly for one instance: it is wrong about a deployment shape rather than wrong in
 * itself, and the framework cannot tell how many replicas you are about to run.
 *
 * <p>Where the loud posture belongs is therefore decidable, and it is the question worth carrying out of
 * this sample: <em>can the absence be noticed later by anything at all?</em> If not, it must be a startup
 * failure. If it can — a metric, a duplicate charge, a log line — a WARN plus a strict switch is enough,
 * and refusing to start would only teach people to set the switch.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class CapabilityAnnouncementTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(ContainerImages.POSTGRES);

  /** Excluding Boot's Kafka auto-configuration leaves no {@code KafkaTemplate}. */
  private static final String NO_TRANSPORT =
      "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration";

  /**
   * Take the broker away and the service refuses to start.
   *
   * <p>Which is a strong thing to do, and the alternative is worse than it sounds. Without a transport the
   * outbox falls back to its in-process dispatcher, which delivers to local listeners and returns
   * normally — so the relay marks every {@code @Externalized} row sent. There is no exception to catch, no
   * dead letter to triage and no consumer lag to alert on, because from every angle the publisher has a
   * clean bill of health. The downstream simply never hears from you, and nobody finds out until someone
   * asks why a report is empty.
   *
   * <p>The one fact the assembly cannot infer is whether a dispatcher can reach outside, so the library
   * makes each one declare it. That single boolean is what turns an undetectable loss into a boot failure.
   */
  @Test
  void publishingIntoADeadEndIsAStartupFailure() {
    assertThatThrownBy(() -> Boot.run(POSTGRES, NO_TRANSPORT))
        .hasStackTraceContaining("@Externalized")
        .hasStackTraceContaining("without it ever leaving this process")
        .hasStackTraceContaining("aipersimmon.ddd.outbox.allow-unreachable-external-events=true");
  }

  /**
   * And it can be accepted, deliberately, by one property — for a local run with no broker.
   *
   * <p>The escape hatch is what makes the refusal reasonable rather than obstructive: developers who
   * cannot start their service will find a way around the check, and the way they find will not log
   * anything. Naming the property in the failure message means the workaround is the supported one, and
   * the supported one still says what is being lost, every time the service starts.
   */
  @Test
  void theLossCanBeAcceptedOnPurposeAndIsStillAnnounced(CapturedOutput output) {
    try (ConfigurableApplicationContext context =
        Boot.run(
            POSTGRES,
            NO_TRANSPORT,
            "--aipersimmon.ddd.outbox.allow-unreachable-external-events=true")) {
      assertThat(context.isRunning()).isTrue();
    }
    assertThat(output).contains("will be marked sent WITHOUT leaving this process");
  }

  /**
   * The edge guard on the in-memory store starts, and warns.
   *
   * <p>This service's application.yaml leaves {@code allow-in-memory-stores} at its default, so the boot
   * that every other test in this module performs is this one — an idempotency guard protecting a single
   * JVM. It is right for the framework not to refuse here: the configuration is correct for one instance,
   * and the framework cannot see the replica count. It is also right for it to say so every time, because
   * the day someone scales to three the guard silently stops guarding anything.
   */
  @Test
  void aguardOnMemoryStartsAndSaysSo(CapturedOutput output) {
    try (ConfigurableApplicationContext context = Boot.run(POSTGRES)) {
      assertThat(context.isRunning()).isTrue();
    }
    assertThat(output).contains("allow-in-memory-stores");
  }

  /**
   * And it can be made to refuse, which is the line a production profile should carry.
   *
   * <p>One property, and the difference between a service that is wrong about its deployment shape and one
   * that cannot be deployed in that shape. Note the asymmetry with the case above: there the strict
   * setting is the default and the escape hatch is opt-in; here the permissive setting is the default and
   * strictness is opt-in. Both defaults are defensible, and a reader who wants one rule will not find one
   * — what they can take away is the question, not a uniform answer.
   */
  @Test
  void thesameGuardCanBeMadeToRefuse() {
    assertThatThrownBy(
            () -> Boot.run(POSTGRES, "--aipersimmon.ddd.web.allow-in-memory-stores=false"))
        .hasStackTraceContaining("allow-in-memory-stores=false");
  }
}

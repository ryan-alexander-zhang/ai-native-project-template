package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * A handler that takes the bus in its constructor has to be able to start.
 *
 * <p>It is the sanctioned way to dispatch a sub-command: the architecture rules forbid a handler
 * depending on another <em>handler</em> and point at the bus instead, and the framework's own bad
 * fixture for the {@code sendAs} rule is written exactly this way. So the composition the rules
 * steer people towards was the one that would not come up — the bus's factory resolved every
 * handler while the bus itself was still being created, and Spring answers a handler asking for the
 * half-built bus with {@code BeanCurrentlyInCreationException}.
 *
 * <p>The fix is to stop resolving handlers during creation. The index is built on first dispatch,
 * and the checks that used to happen as a side effect of building it eagerly now run in a startup
 * validator once the context is complete — the same arrangement the process manager uses.
 */
@SpringBootTest(classes = HandlerInjectingTheBusStartsUpTest.TestApp.class)
class HandlerInjectingTheBusStartsUpTest {

  record Outer(String id) implements Command<String> {}

  record Inner(String id) implements Command<String> {}

  static final List<String> handled = new CopyOnWriteArrayList<>();

  /** The composition under test: a handler holding the bus so it can dispatch a sub-command. */
  static final class OuterHandler implements CommandHandler<Outer, String> {
    private final CommandBus bus;

    OuterHandler(CommandBus bus) {
      this.bus = bus;
    }

    @Override
    public String handle(Outer command, CommandContext context) {
      handled.add("outer:" + command.id());
      return bus.send(new Inner(command.id()), context);
    }
  }

  static final class InnerHandler implements CommandHandler<Inner, String> {
    @Override
    public String handle(Inner command, CommandContext context) {
      handled.add("inner:" + command.id());
      return "done:" + command.id();
    }
  }

  /** Records the context each dispatch ran under, so causation can be asserted. */
  static final class RecordingInterceptor implements com.aipersimmon.ddd.cqrs.CommandInterceptor {
    static final List<CommandContext> contexts = new CopyOnWriteArrayList<>();

    @Override
    public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
      contexts.add(context);
      return invocation.proceed();
    }

    @Override
    public int order() {
      return 5;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {
    @Bean
    RecordingInterceptor recordingInterceptor() {
      return new RecordingInterceptor();
    }

    @Bean
    OuterHandler outerHandler(CommandBus bus) {
      return new OuterHandler(bus);
    }

    @Bean
    InnerHandler innerHandler() {
      return new InnerHandler();
    }
  }

  @Autowired CommandBus bus;

  @Test
  void aHandlerCanHoldTheBusAndDispatchASubCommandThroughIt() {
    handled.clear();

    assertEquals("done:o-1", bus.send(new Outer("o-1")));

    assertEquals(List.of("outer:o-1", "inner:o-1"), handled);
  }

  @Test
  void theSubCommandKeepsTheCorrelationOfTheOneThatCausedIt() {
    handled.clear();
    RecordingInterceptor.contexts.clear();

    bus.send(new Outer("o-2"));

    List<CommandContext> seen = RecordingInterceptor.contexts;
    assertEquals(2, seen.size());
    assertEquals(
        seen.get(0).correlationId(),
        seen.get(1).correlationId(),
        "the sub-command belongs to the same flow");
    assertEquals(
        seen.get(0).messageId(),
        seen.get(1).causationId(),
        "and names the outer command as its cause");
  }

  @Test
  void aCommandWithNoHandlerIsStillRefusedAtDispatch() {
    record Unhandled(String id) implements Command<String> {}

    assertThrows(IllegalStateException.class, () -> bus.send(new Unhandled("x")));
  }

  /**
   * Deferring the index must not defer the check that used to come with building it.
   *
   * <p>A second handler for one command type is a wiring mistake whose symptom, discovered late, is
   * one of the two silently winning. It used to be caught while the bus was constructed; that is
   * the very thing that could not stay. It is caught at the end of context startup instead — still
   * before any request, which is what "fail fast" was buying.
   */
  @Nested
  class ADuplicateHandlerStillFailsStartup {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TwoHandlersForOneCommand {
      @Bean
      InnerHandler one() {
        return new InnerHandler();
      }

      @Bean
      InnerHandler two() {
        return new InnerHandler();
      }
    }

    @Test
    void theContextRefusesToFinishStarting() {
      ApplicationContextRunner runner =
          new ApplicationContextRunner()
              .withUserConfiguration(TwoHandlersForOneCommand.class)
              .withConfiguration(
                  org.springframework.boot.autoconfigure.AutoConfigurations.of(
                      AipersimmonDddCqrsAutoConfiguration.class,
                      com.aipersimmon.ddd.id.AipersimmonDddIdAutoConfiguration.class));

      runner.run(
          context ->
              org.assertj.core.api.Assertions.assertThat(context)
                  .hasFailed()
                  .getFailure()
                  .hasStackTraceContaining("Two command handlers registered for"));
    }
  }
}

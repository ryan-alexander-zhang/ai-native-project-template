package com.aipersimmon.ddd.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A query handler that takes the query bus in its constructor has to be able to start.
 *
 * <p>The composition is the read-side twin of the one {@link HandlerInjectingTheBusStartsUpTest}
 * pins for commands: a composite handler answering one query by asking sub-queries through the bus.
 * The command bus was specifically rebuilt to survive it ({@code RegistryCommandBus}'s lazy
 * registry); the query bus kept resolving every handler while the bus itself was being created, so
 * the same wiring on the read side died with {@code BeanCurrentlyInCreationException}
 * (issue-00139). Both buses must give the same answer to the same consumer shape.
 */
@SpringBootTest(classes = HandlerInjectingTheQueryBusStartsUpTest.TestApp.class)
class HandlerInjectingTheQueryBusStartsUpTest {

  record OuterQuery(String id) implements Query<String> {}

  record InnerQuery(String id) implements Query<String> {}

  static final List<String> handled = new CopyOnWriteArrayList<>();

  /** The composition under test: a handler holding the bus so it can ask a sub-query. */
  static final class OuterHandler implements QueryHandler<OuterQuery, String> {
    private final QueryBus bus;

    OuterHandler(QueryBus bus) {
      this.bus = bus;
    }

    @Override
    public String handle(OuterQuery query) {
      handled.add("outer:" + query.id());
      return bus.ask(new InnerQuery(query.id()));
    }
  }

  static final class InnerHandler implements QueryHandler<InnerQuery, String> {
    @Override
    public String handle(InnerQuery query) {
      handled.add("inner:" + query.id());
      return "answer:" + query.id();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {
    @org.springframework.context.annotation.Bean
    OuterHandler outerHandler(QueryBus bus) {
      return new OuterHandler(bus);
    }

    @org.springframework.context.annotation.Bean
    InnerHandler innerHandler() {
      return new InnerHandler();
    }
  }

  @Autowired QueryBus bus;

  @Test
  void aHandlerCanHoldTheBusAndAskASubQueryThroughIt() {
    handled.clear();

    assertEquals("answer:q-1", bus.ask(new OuterQuery("q-1")));

    assertEquals(List.of("outer:q-1", "inner:q-1"), handled);
  }

  @Test
  void aQueryWithNoHandlerIsStillRefusedAtDispatch() {
    record Unhandled(String id) implements Query<String> {}

    assertThrows(IllegalStateException.class, () -> bus.ask(new Unhandled("x")));
  }

  /**
   * Deferring the index must not defer the check that used to come with building it: two handlers
   * for one query type still refuse to finish starting, just at the end of context startup instead
   * of during bus construction.
   */
  @Nested
  class ADuplicateHandlerStillFailsStartup {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TwoHandlersForOneQuery {
      @org.springframework.context.annotation.Bean
      InnerHandler one() {
        return new InnerHandler();
      }

      @org.springframework.context.annotation.Bean
      InnerHandler two() {
        return new InnerHandler();
      }
    }

    @Test
    void theContextRefusesToFinishStarting() {
      ApplicationContextRunner runner =
          new ApplicationContextRunner()
              .withUserConfiguration(TwoHandlersForOneQuery.class)
              .withConfiguration(
                  org.springframework.boot.autoconfigure.AutoConfigurations.of(
                      AipersimmonDddCqrsAutoConfiguration.class,
                      com.aipersimmon.ddd.id.AipersimmonDddIdAutoConfiguration.class));

      runner.run(
          context ->
              org.assertj.core.api.Assertions.assertThat(context)
                  .hasFailed()
                  .getFailure()
                  .hasStackTraceContaining("Two query handlers registered for"));
    }
  }
}

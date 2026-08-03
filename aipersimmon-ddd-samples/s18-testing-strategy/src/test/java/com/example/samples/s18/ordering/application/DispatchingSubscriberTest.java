package com.example.samples.s18.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.test.RecordingCommandBus;
import com.aipersimmon.ddd.test.WithTenant;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s18.ordering.domain.OrderId;
import com.example.samples.s18.ordering.domain.OrderPlacedInContext;
import org.junit.jupiter.api.Test;

/**
 * Layer 2, continued — components whose whole job is to dispatch something.
 *
 * <p>{@code RecordingCommandBus} answers the question these components exist to raise: <em>which</em>
 * command, with <em>which</em> context. Asserting on a real bus would need handlers, a transaction
 * manager and a database to observe the same thing indirectly.
 */
class DispatchingSubscriberTest {

  private final RecordingCommandBus commandBus = new RecordingCommandBus();

  @Test
  void asmallOrderIsConfirmedAutomatically() {
    new AutoConfirmSmallOrders(commandBus).on(placed(500));

    assertThat(commandBus.commandsOf(ConfirmOrder.class))
        .containsExactly(new ConfirmOrder("order-1"));
    // Nothing was in flight, so the dispatch is a root: correlation equals its own message id.
    assertThat(commandBus.dispatches().get(0).kind()).isEqualTo(RecordingCommandBus.Kind.ROOT);
  }

  @Test
  void alargeOrderIsLeftAlone() {
    new AutoConfirmSmallOrders(commandBus).on(placed(50_000));

    assertThat(commandBus.dispatches()).isEmpty();
  }

  @Test
  @WithTenant("acme")
  void theDispatchInheritsTheAmbientTenant() {
    new AutoConfirmSmallOrders(commandBus).on(placed(500));

    // @WithTenant binds the tenant for the duration of the test, so this asserts the propagation the
    // production bus would do — without a request, a filter or a database.
    assertThat(commandBus.contexts().get(0).tenantId().value()).isEqualTo("acme");
  }

  private static OrderPlacedInContext placed(long amountCents) {
    return new OrderPlacedInContext(new OrderId("order-1"), "customer-1", amountCents);
  }

  /** Kept to show the shape a context-carrying dispatch has; see {@code send(command, cause)}. */
  @Test
  void adispatchWithACauseIsRecordedAsAChild() {
    CommandContext cause = CommandContext.root(Tenants.ROOT, "message-1");

    commandBus.send(new ConfirmOrder("order-9"), cause);

    assertThat(commandBus.dispatches().get(0).kind()).isEqualTo(RecordingCommandBus.Kind.CHILD);
    assertThat(commandBus.contexts().get(0).causationId()).isEqualTo("message-1");
    assertThat(commandBus.contexts().get(0).correlationId()).isEqualTo(cause.correlationId());
  }
}

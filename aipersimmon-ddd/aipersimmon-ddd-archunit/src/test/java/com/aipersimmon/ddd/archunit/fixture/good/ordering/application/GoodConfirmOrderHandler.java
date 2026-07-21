package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodOrder;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;

/**
 * Well-behaved command handler: it depends inward on the domain ({@link GoodOrder}) and reuses
 * shared orchestration through a non-handler application collaborator ({@link
 * GoodPlaceOrderService}) — never on another {@link CommandHandler}. This is the shape {@code
 * commandHandlersShouldNotDependOnOtherCommandHandlers} permits. Marked {@link UseCase @UseCase}
 * and placed in the application layer, so it also satisfies {@code
 * commandAndQueryHandlersShouldResideInApplication} and {@code useCasesShouldResideInApplication}.
 */
@UseCase
public class GoodConfirmOrderHandler implements CommandHandler<GoodConfirmOrder, Void> {

  private final GoodPlaceOrderService orderService;

  public GoodConfirmOrderHandler(GoodPlaceOrderService orderService) {
    this.orderService = orderService;
  }

  @Override
  public Void handle(GoodConfirmOrder command, CommandContext context) {
    GoodOrder order = new GoodOrder(command.orderId());
    orderService.idOf(order);
    return null;
  }
}

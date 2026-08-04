package com.example.samples.s12.ordering.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s12.ordering.application.RebuildOrderList;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's endpoint, and the reason a projection is safe to own.
 *
 * <p>A read model you cannot rebuild on demand is a read model whose every bug is a data-repair project. This
 * one is a POST. It is unauthenticated here because samples have no security layer (S14's subject) — in a real
 * deployment it is an admin route, because it is briefly destructive.
 */
@RestController
class ProjectionAdminController {

  private final CommandBus commandBus;

  ProjectionAdminController(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @PostMapping("/admin/order-list/rebuild")
  Map<String, Integer> rebuild() {
    return Map.of("ordersProjected", commandBus.send(new RebuildOrderList()));
  }
}

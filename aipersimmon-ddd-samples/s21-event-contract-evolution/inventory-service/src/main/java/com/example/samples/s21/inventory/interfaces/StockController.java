package com.example.samples.s21.inventory.interfaces;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s21.inventory.application.FindStock;
import com.example.samples.s21.inventory.application.StockView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto stock, so what an upcasted event did is observable from outside.
 *
 * <p>The endpoint hands the bus two strings and never names {@code StockLocation}. That keeps the
 * identity this context introduced — the reason the event contract needed a version 2 at all — an
 * internal decision rather than something a URL shape has to agree with.
 */
@RestController
@RequestMapping("/stock")
class StockController {

  private final QueryBus queryBus;

  StockController(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @GetMapping("/{warehouse}/{sku}")
  ResponseEntity<StockView> stock(@PathVariable String warehouse, @PathVariable String sku) {
    return queryBus
        .ask(new FindStock(warehouse, sku))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

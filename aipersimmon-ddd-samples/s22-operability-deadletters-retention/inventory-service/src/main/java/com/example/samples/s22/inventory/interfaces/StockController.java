package com.example.samples.s22.inventory.interfaces;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s22.inventory.application.FindStock;
import com.example.samples.s22.inventory.application.StockView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A window onto stock, so a replayed or quarantined message's effect is observable from outside. */
@RestController
@RequestMapping("/stock")
class StockController {

  private final QueryBus queryBus;

  StockController(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @GetMapping("/{sku}")
  ResponseEntity<StockView> stock(@PathVariable String sku) {
    return queryBus
        .ask(new FindStock(sku))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

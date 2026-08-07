package com.example.samples.s04.inventory.adapter;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s04.inventory.application.FindStock;
import com.example.samples.s04.inventory.application.StockView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto this service's stock, so what the ordering context's event did is observable from
 * outside.
 *
 * <p>It asks the query bus rather than the repository. The difference is not ceremony: the read now
 * runs inside the transaction and the interceptor chain the bus establishes, and the shape of the
 * answer lives in a {@code @ReadModel} that a second caller can reuse instead of in a response map
 * only this class can build.
 */
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

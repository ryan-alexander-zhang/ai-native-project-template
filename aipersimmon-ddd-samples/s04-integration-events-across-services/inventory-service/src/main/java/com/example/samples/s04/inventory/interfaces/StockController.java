package com.example.samples.s04.inventory.interfaces;

import com.example.samples.s04.inventory.domain.Sku;
import com.example.samples.s04.inventory.domain.StockItem;
import com.example.samples.s04.inventory.domain.StockItems;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto the result, so the chain can be observed from outside the process. A read this small
 * loading the aggregate is the legitimate case {@code Query}'s own javadoc allows; the list-shaped
 * treatment is S20.
 */
@RestController
@RequestMapping("/stock")
class StockController {

  private final StockItems stockItems;

  StockController(StockItems stockItems) {
    this.stockItems = stockItems;
  }

  @GetMapping("/{sku}")
  ResponseEntity<Map<String, Object>> stock(@PathVariable String sku) {
    return stockItems
        .findBySku(new Sku(sku))
        .map(this::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private Map<String, Object> body(StockItem item) {
    return Map.of(
        "sku", item.id().value(),
        "available", item.available(),
        "reserved", item.reserved());
  }
}

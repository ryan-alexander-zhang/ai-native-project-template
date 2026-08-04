package com.example.samples.s22.inventory.interfaces;

import com.example.samples.s22.inventory.domain.Sku;
import com.example.samples.s22.inventory.domain.StockItems;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What has been reserved, for a human or a test to look at. */
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
        .map(
            item ->
                Map.<String, Object>of(
                    "sku", item.id().value(),
                    "available", item.available(),
                    "reserved", item.reserved()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

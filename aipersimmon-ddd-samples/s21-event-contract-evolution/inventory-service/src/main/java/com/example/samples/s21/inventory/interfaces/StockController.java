package com.example.samples.s21.inventory.interfaces;

import com.example.samples.s21.inventory.domain.StockItem;
import com.example.samples.s21.inventory.domain.StockItems;
import com.example.samples.s21.inventory.domain.StockLocation;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto the result, so which warehouse a record landed in is observable from outside — which is
 * how "the upcast invented nothing" becomes something a person can check by hand.
 */
@RestController
@RequestMapping("/stock")
class StockController {

  private final StockItems stockItems;

  StockController(StockItems stockItems) {
    this.stockItems = stockItems;
  }

  @GetMapping("/{warehouse}/{sku}")
  ResponseEntity<Map<String, Object>> stock(
      @PathVariable String warehouse, @PathVariable String sku) {
    return stockItems
        .find(new StockLocation(sku, warehouse))
        .map(this::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private Map<String, Object> body(StockItem item) {
    return Map.of(
        "sku", item.id().sku(),
        "warehouse", item.id().warehouse(),
        "available", item.available(),
        "reserved", item.reserved());
  }
}

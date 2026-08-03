package com.example.samples.s05.catalog.interfaces;

import com.example.samples.s05.catalog.domain.Product;
import com.example.samples.s05.catalog.domain.Products;
import com.example.samples.s05.catalog.domain.Sku;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto the mirror, so what the ERP's messages did is observable from outside.
 *
 * <p>It exposes {@code upstreamRevision} deliberately: "which version of upstream truth am I looking at"
 * is the first question anyone debugging a stale mirror asks, and a mirror that cannot answer it forces
 * the answer to be guessed from timestamps.
 */
@RestController
@RequestMapping("/products")
class ProductController {

  private final Products products;

  ProductController(Products products) {
    this.products = products;
  }

  @GetMapping("/{sku}")
  ResponseEntity<Map<String, Object>> product(@PathVariable String sku) {
    return products
        .find(new Sku(sku))
        .map(this::body)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private Map<String, Object> body(Product product) {
    return Map.of(
        "sku", product.id().value(),
        "name", product.name(),
        "priceCents", product.priceCents(),
        "upstreamRevision", product.upstreamRevision());
  }
}

package com.example.samples.s05.catalog.adapter;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s05.catalog.application.FindProduct;
import com.example.samples.s05.catalog.application.ProductView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window onto the mirror, so what the ERP's messages did is observable from outside.
 *
 * <p>The endpoint asks the query bus; what the answer contains — including {@code upstreamRevision}
 * — is stated once on {@link ProductView} rather than assembled here.
 */
@RestController
@RequestMapping("/products")
class ProductController {

  private final QueryBus queryBus;

  ProductController(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @GetMapping("/{sku}")
  ResponseEntity<ProductView> product(@PathVariable String sku) {
    return queryBus
        .ask(new FindProduct(sku))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}

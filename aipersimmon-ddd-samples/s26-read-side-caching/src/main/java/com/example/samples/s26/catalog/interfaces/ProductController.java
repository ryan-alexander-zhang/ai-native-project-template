package com.example.samples.s26.catalog.interfaces;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.samples.s26.catalog.application.AddProduct;
import com.example.samples.s26.catalog.application.ProductDetail;
import com.example.samples.s26.catalog.application.ProductDetailQuery;
import com.example.samples.s26.catalog.application.RecordSale;
import com.example.samples.s26.catalog.application.RenameProduct;
import com.example.samples.s26.catalog.application.RepriceProduct;
import com.example.samples.s26.catalog.application.TopSeller;
import com.example.samples.s26.catalog.application.TopSellersQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product endpoints.
 *
 * <p>Nothing here mentions the cache, and the two read endpoints look identical from the outside even though
 * one is answered from Redis most of the time and the other never is. That symmetry is the interceptor's
 * doing, and it is what keeps caching a deployment concern rather than an API one — no {@code ?fresh=true}
 * parameter, no cache header for callers to reason about, nothing a client could come to depend on.
 */
@RestController
class ProductController {

  private final CommandBus commands;
  private final QueryBus queries;

  ProductController(CommandBus commands, QueryBus queries) {
    this.commands = commands;
    this.queries = queries;
  }

  record AddRequest(@NotBlank String sku, @NotBlank String name, @Positive long priceCents) {}

  record RenameRequest(@NotBlank String name) {}

  record RepriceRequest(@Positive long priceCents) {}

  record SaleRequest(@Positive int quantity) {}

  @PostMapping("/products")
  ResponseEntity<Void> add(@Valid @RequestBody AddRequest request) {
    commands.send(new AddProduct(request.sku(), request.name(), request.priceCents()));
    return ResponseEntity.created(URI.create("/products/" + request.sku())).build();
  }

  /** The cached read. */
  @GetMapping("/products/{sku}")
  ProductDetail detail(@PathVariable String sku) {
    return queries.ask(new ProductDetailQuery(sku));
  }

  /**
   * The projection read. Same shape of endpoint, no cache behind it.
   *
   * <p>Not {@code /products/top}, which would be a literal path competing with {@code /products/{sku}}: Spring
   * resolves that in favour of the literal, so it works — right up until somebody adds a product whose sku is
   * {@code top} and finds it unreachable. A collection's derived views belong beside it, not inside its id
   * space.
   */
  @GetMapping("/best-sellers")
  List<TopSeller> top(@RequestParam(defaultValue = "10") int limit) {
    return queries.ask(new TopSellersQuery(limit));
  }

  @PostMapping("/products/{sku}/name")
  ResponseEntity<Void> rename(@PathVariable String sku, @Valid @RequestBody RenameRequest request) {
    commands.send(new RenameProduct(sku, request.name()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/products/{sku}/price")
  ResponseEntity<Void> reprice(
      @PathVariable String sku, @Valid @RequestBody RepriceRequest request) {
    commands.send(new RepriceProduct(sku, request.priceCents()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/products/{sku}/sales")
  ResponseEntity<Void> sell(@PathVariable String sku, @Valid @RequestBody SaleRequest request) {
    commands.send(new RecordSale(sku, request.quantity()));
    return ResponseEntity.accepted().build();
  }
}

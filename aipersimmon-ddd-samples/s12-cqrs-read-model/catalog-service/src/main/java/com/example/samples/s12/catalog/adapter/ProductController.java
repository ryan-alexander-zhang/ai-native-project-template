package com.example.samples.s12.catalog.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s12.catalog.application.RenameProduct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue's edge.
 *
 * <p>The read endpoint here is the honest, boring one: a product's name served from the table that owns it,
 * with no projection anywhere. Worth having in the sample as the counterexample — <strong>most read
 * endpoints look like this and should.</strong>
 */
@RestController
class ProductController {

  private final CommandBus commandBus;
  private final JdbcTemplate jdbc;

  ProductController(CommandBus commandBus, JdbcTemplate jdbc) {
    this.commandBus = commandBus;
    this.jdbc = jdbc;
  }

  @PostMapping("/products/{sku}/rename")
  void rename(@PathVariable String sku, @Valid @RequestBody RenameRequest request) {
    commandBus.send(new RenameProduct(sku, request.name()));
  }

  @GetMapping("/products/{sku}")
  ProductView view(@PathVariable String sku) {
    return jdbc.queryForObject(
        "SELECT name FROM s12_product WHERE sku = ?",
        (rs, row) -> new ProductView(sku, rs.getString("name")),
        sku);
  }

  record RenameRequest(@NotBlank String name) {}

  record ProductView(String sku, String name) {}
}

package com.example.samples.s12.catalog.domain;

import java.util.Optional;

/** The product aggregate's port. */
public interface Products {

  Optional<Product> find(Sku sku);

  void save(Product product);
}

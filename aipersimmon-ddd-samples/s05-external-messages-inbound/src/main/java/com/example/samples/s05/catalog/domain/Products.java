package com.example.samples.s05.catalog.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port. */
@Repository
public interface Products {

  void save(Product product);

  Optional<Product> find(Sku sku);
}

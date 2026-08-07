package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one mirrored product. Empty when the ERP has never sent one. */
public record FindProduct(String sku) implements Query<Optional<ProductView>> {}

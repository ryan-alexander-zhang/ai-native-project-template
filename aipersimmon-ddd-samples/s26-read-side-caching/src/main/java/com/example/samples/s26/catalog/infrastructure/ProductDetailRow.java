package com.example.samples.s26.catalog.infrastructure;

/**
 * The detail query's result row.
 *
 * <p>A mutable row with setters rather than the {@code ProductDetail} record directly, because MyBatis maps
 * results by property and only maps to a record's constructor when argument-name-based constructor
 * automapping is switched on — a setting whose absence produces a null-filled object rather than an error.
 * The adapter does the one-line conversion where it can be seen.
 */
class ProductDetailRow {

  private String sku;
  private String name;
  private Long priceCents;
  private Long soldRecently;

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  Long getPriceCents() {
    return priceCents;
  }

  void setPriceCents(Long priceCents) {
    this.priceCents = priceCents;
  }

  Long getSoldRecently() {
    return soldRecently;
  }

  void setSoldRecently(Long soldRecently) {
    this.soldRecently = soldRecently;
  }
}

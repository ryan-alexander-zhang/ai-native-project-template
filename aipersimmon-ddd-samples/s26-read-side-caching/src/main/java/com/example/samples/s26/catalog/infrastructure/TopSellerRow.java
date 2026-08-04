package com.example.samples.s26.catalog.infrastructure;

/** One row of the best-sellers read. */
class TopSellerRow {

  private String sku;
  private String name;
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

  Long getSoldRecently() {
    return soldRecently;
  }

  void setSoldRecently(Long soldRecently) {
    this.soldRecently = soldRecently;
  }
}

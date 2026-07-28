package com.example.inventory.infrastructure.persistence;

/** One {@code inventory.stocks} row as the read side needs it: the SKU and its availability. */
public class StockLevelRow {

  private String sku;
  private Integer available;

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public Integer getAvailable() {
    return available;
  }

  public void setAvailable(Integer available) {
    this.available = available;
  }
}

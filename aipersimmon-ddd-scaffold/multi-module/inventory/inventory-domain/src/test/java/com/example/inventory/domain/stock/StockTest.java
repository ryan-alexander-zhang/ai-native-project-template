package com.example.inventory.domain.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class StockTest {

  private static final Sku SKU = new Sku("sku-1");

  @Test
  void rejectsNegativeInitialAvailable() {
    assertThrows(DomainException.class, () -> new Stock(SKU, -1));
  }

  @Test
  void exposesSkuAndAvailable() {
    Stock stock = new Stock(SKU, 10);

    assertSame(SKU, stock.id());
    assertEquals(10, stock.available());
  }

  @Test
  void reserveDecrementsAvailable() {
    Stock stock = new Stock(SKU, 10);

    stock.reserve(3);

    assertEquals(7, stock.available());
  }

  @Test
  void reserveTheExactAvailableIsAllowed() {
    Stock stock = new Stock(SKU, 10);

    stock.reserve(10);

    assertEquals(0, stock.available());
  }

  @Test
  void reserveRejectsNonPositiveQuantity() {
    Stock stock = new Stock(SKU, 10);

    assertThrows(DomainException.class, () -> stock.reserve(0));
    assertEquals(10, stock.available(), "a rejected reserve does not change available");
  }

  @Test
  void reserveMoreThanAvailableFailsWithInsufficientStockCode() {
    Stock stock = new Stock(SKU, 10);

    DomainException ex = assertThrows(DomainException.class, () -> stock.reserve(11));

    assertSame(InventoryErrorCode.INSUFFICIENT_STOCK, ex.errorCode().orElseThrow());
    assertEquals(10, stock.available());
  }

  @Test
  void releaseIncrementsAvailable() {
    Stock stock = new Stock(SKU, 10);

    stock.release(5);

    assertEquals(15, stock.available());
  }

  @Test
  void releaseRejectsNonPositiveQuantity() {
    Stock stock = new Stock(SKU, 10);

    assertThrows(DomainException.class, () -> stock.release(0));
  }
}

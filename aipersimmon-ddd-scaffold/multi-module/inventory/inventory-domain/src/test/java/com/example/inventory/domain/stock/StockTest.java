package com.example.inventory.domain.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class StockTest {

  @Test
  void rejectsANullSku() {
    // The SKU is the aggregate's identity: a null one would flow into equals/hashCode and the
    // repository's key instead of failing here at the door.
    assertThrows(DomainException.class, () -> new Stock(null, 5));
  }

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

  @Test
  void reconstituteCarriesThePersistedVersionAndRecordsNoEvents() {
    Stock stock = Stock.reconstitute(SKU, 7, 4L);

    assertSame(SKU, stock.id());
    assertEquals(7, stock.available());
    assertEquals(4L, stock.version(), "the loaded version is what the repository checks on save");
    assertTrue(stock.domainEvents().isEmpty(), "reconstitution records no events");
  }

  @Test
  void reconstituteStillRejectsANegativeAvailable() {
    assertThrows(DomainException.class, () -> Stock.reconstitute(SKU, -1, 1L));
  }
}

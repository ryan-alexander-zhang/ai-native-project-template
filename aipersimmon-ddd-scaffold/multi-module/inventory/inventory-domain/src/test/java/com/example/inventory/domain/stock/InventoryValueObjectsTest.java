package com.example.inventory.domain.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class InventoryValueObjectsTest {

  @Test
  void skuHoldsItsValue() {
    assertEquals("sku-1", new Sku("sku-1").value());
  }

  @Test
  void skuRejectsNullOrBlank() {
    assertThrows(DomainException.class, () -> new Sku(null));
    assertThrows(DomainException.class, () -> new Sku(" "));
  }

  @Test
  void reservationIdHoldsItsValue() {
    assertEquals("r-1", new ReservationId("r-1").value());
  }

  @Test
  void reservationIdRejectsNullOrBlank() {
    assertThrows(DomainException.class, () -> new ReservationId(null));
    assertThrows(DomainException.class, () -> new ReservationId(" "));
  }

  @Test
  void errorCodesCarryStableCodeAndCategory() {
    assertEquals("inventory.insufficient-stock", InventoryErrorCode.INSUFFICIENT_STOCK.code());
    assertEquals(ErrorCategory.DOMAIN_RULE, InventoryErrorCode.INSUFFICIENT_STOCK.category());

    assertEquals("inventory.stock-not-found", InventoryErrorCode.STOCK_NOT_FOUND.code());
    assertEquals(ErrorCategory.NOT_FOUND, InventoryErrorCode.STOCK_NOT_FOUND.category());

    assertEquals(
        "inventory.reservation-not-found", InventoryErrorCode.RESERVATION_NOT_FOUND.code());
    assertEquals(ErrorCategory.NOT_FOUND, InventoryErrorCode.RESERVATION_NOT_FOUND.category());
  }
}

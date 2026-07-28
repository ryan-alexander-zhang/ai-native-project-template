package com.example.ordering.domain.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

/** Ordering's own {@link Sku} value object (issue-00085). */
class SkuTest {

  @Test
  void twoSkusWithTheSameValueAreEqual() {
    assertEquals(new Sku("SKU-1"), new Sku("SKU-1"));
    assertNotEquals(new Sku("SKU-1"), new Sku("SKU-2"));
  }

  @Test
  void aBlankSkuIsRejectedOnceHereRatherThanAtEveryCallSite() {
    assertThrows(DomainException.class, () -> new Sku(null));
    assertThrows(DomainException.class, () -> new Sku(""));
    assertThrows(DomainException.class, () -> new Sku("  "));
  }

  @Test
  void itReadsAsItsValueInAMessage() {
    // ManualReviewPolicy interpolates a SKU into the reason it hands an operator, so the default
    // record toString ("Sku[value=SKU-1]") would leak the wrapper into text a person reads.
    assertEquals("restricted: SKU-1", "restricted: " + new Sku("SKU-1"));
  }
}

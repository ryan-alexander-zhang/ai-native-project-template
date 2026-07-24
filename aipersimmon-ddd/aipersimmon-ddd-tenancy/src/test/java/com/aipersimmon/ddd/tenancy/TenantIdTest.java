package com.aipersimmon.ddd.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TenantIdTest {

  @Test
  void rejectsNullOrBlank() {
    assertThrows(IllegalArgumentException.class, () -> new TenantId(null));
    assertThrows(IllegalArgumentException.class, () -> new TenantId("   "));
  }

  @Test
  void rejectsOverMaxLength() {
    String tooLong = "a".repeat(TenantId.MAX_LENGTH + 1);
    assertThrows(IllegalArgumentException.class, () -> new TenantId(tooLong));
  }

  @Test
  void keepsValueAndComparesByValue() {
    assertEquals(new TenantId("acme"), new TenantId("acme"));
    assertEquals("acme", new TenantId("acme").value());
    assertEquals("acme", new TenantId("acme").toString());
  }
}

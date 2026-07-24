package com.aipersimmon.ddd.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TenantsTest {

  @Test
  void rootIsTheSentinel() {
    assertEquals("__root__", Tenants.ROOT.value());
  }

  @Test
  void ofRejectsReservedPrefix() {
    assertThrows(IllegalArgumentException.class, () -> Tenants.of("__whatever"));
  }

  @Test
  void ofAcceptsANormalTenant() {
    assertEquals("acme", Tenants.of("acme").value());
  }

  @Test
  void fromValueReconstitutesAnyTrustedValueIncludingTheSentinel() {
    assertEquals("acme", Tenants.fromValue("acme").value());
    assertEquals(Tenants.ROOT, Tenants.fromValue("__root__"));
  }
}

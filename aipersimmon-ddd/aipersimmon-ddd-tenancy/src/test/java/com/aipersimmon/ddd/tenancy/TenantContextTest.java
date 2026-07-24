package com.aipersimmon.ddd.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void currentIsEmptyByDefault() {
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void setThenRequireReturnsIt() {
    TenantContext.set(Tenants.of("acme"));
    assertEquals(Tenants.of("acme"), TenantContext.require());
  }

  @Test
  void requireThrowsWhenUnbound() {
    assertThrows(IllegalStateException.class, TenantContext::require);
  }

  @Test
  void runAsBindsForTheScopeAndClearsAfter() {
    assertTrue(TenantContext.current().isEmpty());
    String seen = TenantContext.runAs(Tenants.of("acme"), () -> TenantContext.require().value());
    assertEquals("acme", seen);
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void runAsRestoresThePreviousBindingWhenNested() {
    TenantContext.set(Tenants.of("outer"));
    TenantContext.runAs(
        Tenants.of("inner"), () -> assertEquals("inner", TenantContext.require().value()));
    assertEquals("outer", TenantContext.require().value());
  }
}

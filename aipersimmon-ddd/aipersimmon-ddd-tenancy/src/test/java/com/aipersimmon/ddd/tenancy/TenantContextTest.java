package com.aipersimmon.ddd.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  @AfterEach
  void tearDown() {
    TenantContext.clear();
    TenantContext.setRequired(false);
  }

  @Test
  void currentIsEmptyByDefault() {
    assertTrue(TenantContext.current().isEmpty());
  }

  /**
   * The enforcement flag is an isolation guarantee, and {@link TenantEnforcement} is its only
   * sanctioned mover — registered as a bean whose lifecycle brackets the application context. A
   * public {@code setRequired} let any code in the process flip fail-closed resolution back to
   * sentinel fallback at runtime, with a javadoc plea as the only guard; package-private makes the
   * compiler enforce what the javadoc used to ask for.
   */
  @Test
  void theEnforcementFlagCannotBeMovedFromOutsideThePackage() throws NoSuchMethodException {
    var setRequired = TenantContext.class.getDeclaredMethod("setRequired", boolean.class);
    assertFalse(
        java.lang.reflect.Modifier.isPublic(setRequired.getModifiers()),
        "setRequired must not be public; TenantEnforcement is the only sanctioned mover");
  }

  @Test
  void effectiveReturnsTheBoundTenant() {
    TenantContext.set(Tenants.of("acme"));
    assertEquals(Tenants.of("acme"), TenantContext.effective());
  }

  @Test
  void effectiveIsTheRootSentinelWhenUnboundAndTenancyIsOff() {
    assertEquals(Tenants.ROOT, TenantContext.effective());
  }

  @Test
  void effectiveThrowsWhenUnboundAndTenancyIsOn() {
    TenantContext.setRequired(true);
    MissingTenantException thrown =
        assertThrows(MissingTenantException.class, TenantContext::effective);
    assertTrue(
        thrown.getMessage().contains("runAs"),
        "the message should name the fix, was: " + thrown.getMessage());
  }

  @Test
  void effectiveReturnsTheBoundTenantWhenTenancyIsOn() {
    TenantContext.setRequired(true);
    TenantContext.set(Tenants.of("acme"));
    assertEquals(Tenants.of("acme"), TenantContext.effective());
  }

  @Test
  void enforcementFlipsTheModeAndRestoresIt() {
    TenantEnforcement enforcement = new TenantEnforcement();
    assertFalse(TenantContext.isRequired());
    enforcement.enable();
    assertTrue(TenantContext.isRequired());
    enforcement.disable();
    assertFalse(TenantContext.isRequired());
  }

  @Test
  void runAsBindsForTheScopeAndClearsAfter() {
    assertTrue(TenantContext.current().isEmpty());
    String seen = TenantContext.runAs(Tenants.of("acme"), () -> TenantContext.effective().value());
    assertEquals("acme", seen);
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void runAsRestoresThePreviousBindingWhenNested() {
    TenantContext.set(Tenants.of("outer"));
    TenantContext.runAs(
        Tenants.of("inner"), () -> assertEquals("inner", TenantContext.effective().value()));
    assertEquals("outer", TenantContext.effective().value());
  }
}

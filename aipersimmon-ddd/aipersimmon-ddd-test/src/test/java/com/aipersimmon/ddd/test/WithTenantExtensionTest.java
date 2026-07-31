package com.aipersimmon.ddd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.Optional;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * What {@link WithTenant} promises: bound before the test, the method's value over the class's, and
 * — the part every hand-written {@code @AfterEach} eventually forgets — cleared afterwards, so no
 * tenant leaks through the ThreadLocal into the next test on the same worker.
 *
 * <p>The tests are explicitly ordered because the cleanup claim is inherently about what the
 * <em>next</em> test observes on the same thread.
 */
@WithTenant("acme")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WithTenantExtensionTest {

  @Test
  @Order(1)
  void theClassLevelTenantIsBoundBeforeTheTest() {
    assertEquals(Optional.of(Tenants.of("acme")), TenantContext.current());
  }

  @Test
  @Order(2)
  @WithTenant("globex")
  void aMethodLevelTenantOverridesTheClass() {
    assertEquals(Optional.of(Tenants.of("globex")), TenantContext.current());
  }

  @Test
  @Order(3)
  void theBindingIsFreshPerTestNotLeakedFromThePreviousOne() {
    // Order(2) bound globex on this very thread; if cleanup were missing, this would see it.
    assertEquals(Optional.of(Tenants.of("acme")), TenantContext.current());
  }

  @Test
  @Order(4)
  void aRebindInsideATestDoesNotSurviveIt() {
    TenantContext.set(Tenants.of("initech"));
    assertTrue(TenantContext.current().isPresent());
  }

  @Test
  @Order(5)
  void theRebindFromThePreviousTestIsGoneToo() {
    // The cleanup is unconditional — it clears whatever the test left bound, not merely what the
    // extension itself bound, so even a mid-test rebind cannot leak.
    assertEquals(Optional.of(Tenants.of("acme")), TenantContext.current());
  }
}

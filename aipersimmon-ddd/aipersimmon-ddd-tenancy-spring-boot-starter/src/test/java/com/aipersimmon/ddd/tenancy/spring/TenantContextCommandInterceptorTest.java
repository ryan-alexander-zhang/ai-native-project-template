package com.aipersimmon.ddd.tenancy.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextCommandInterceptorTest {

  record Ping() implements Command<String> {}

  private final TenantContextCommandInterceptor interceptor = new TenantContextCommandInterceptor();

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void bindsTheCommandsTenantForTheDurationOfHandling() {
    CommandContext ctx = CommandContext.root(Tenants.of("acme"), "cmd-1");

    String seenDuringHandling =
        interceptor.intercept(new Ping(), ctx, () -> TenantContext.effective().value());

    assertEquals("acme", seenDuringHandling);
    assertTrue(TenantContext.current().isEmpty(), "tenant is cleared after handling");
  }

  @Test
  void restoresThePreviousAmbientTenantAfterHandling() {
    TenantContext.set(Tenants.of("outer"));

    interceptor.intercept(
        new Ping(), CommandContext.root(Tenants.of("inner"), "cmd-2"), () -> null);

    assertEquals("outer", TenantContext.effective().value());
  }

  @Test
  void isOrderedOutside() {
    assertEquals(-90, interceptor.order());
  }
}

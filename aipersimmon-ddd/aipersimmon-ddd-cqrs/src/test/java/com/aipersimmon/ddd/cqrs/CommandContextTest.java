package com.aipersimmon.ddd.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import org.junit.jupiter.api.Test;

class CommandContextTest {

  private static final TenantId ACME = Tenants.of("acme");

  // There is no rejectsBlankTenantId test any more, and that is the point: a blank — or otherwise
  // malformed — tenant is unrepresentable as a TenantId, so the case this class used to have to
  // guard against cannot reach it. Forging an identity now requires an explicit Tenants.of /
  // Tenants.fromValue call, the declared trust-boundary act.

  @Test
  void rejectsNullTenant() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext(null, "msg", "corr", null));
  }

  @Test
  void rejectsNullMessageId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommandContext(ACME, null, "corr", null));
  }

  @Test
  void rejectsBlankMessageId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(ACME, " ", "corr", null));
  }

  @Test
  void rejectsNullCorrelationId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(ACME, "msg", null, null));
  }

  @Test
  void rejectsBlankCorrelationId() {
    assertThrows(IllegalArgumentException.class, () -> new CommandContext(ACME, "msg", " ", null));
  }

  @Test
  void acceptsNullCausationForARootCommand() {
    CommandContext ctx = new CommandContext(ACME, "msg", "corr", null);

    assertEquals(ACME, ctx.tenantId());
    assertEquals("msg", ctx.messageId());
    assertEquals("corr", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void rootUnderTheSentinelTenantSeedsCorrelationToItsOwnId() {
    CommandContext ctx = CommandContext.root(Tenants.ROOT, "cmd-1");

    assertEquals(Tenants.ROOT, ctx.tenantId());
    assertEquals("cmd-1", ctx.messageId());
    assertEquals("cmd-1", ctx.correlationId());
    assertNull(ctx.causationId());
  }

  @Test
  void rootUnderAnExplicitTenantCarriesThatTenant() {
    CommandContext ctx = CommandContext.root(ACME, "cmd-1");

    assertEquals(ACME, ctx.tenantId());
    assertEquals("cmd-1", ctx.messageId());
    assertEquals("cmd-1", ctx.correlationId());
  }

  @Test
  void deriveChildKeepsTenantAndCorrelationAndRecordsThisAsCause() {
    CommandContext parent = CommandContext.root(ACME, "cmd-1");

    CommandContext child = parent.deriveChild("cmd-2");

    assertEquals(ACME, child.tenantId());
    assertEquals("cmd-2", child.messageId());
    assertEquals("cmd-1", child.correlationId());
    assertEquals("cmd-1", child.causationId());
  }
}

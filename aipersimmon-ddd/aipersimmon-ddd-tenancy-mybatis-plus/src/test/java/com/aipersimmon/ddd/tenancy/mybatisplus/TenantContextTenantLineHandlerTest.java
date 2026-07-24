package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.List;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;

class TenantContextTenantLineHandlerTest {

  private final TenantContextTenantLineHandler handler =
      new TenantContextTenantLineHandler("tenant_id", List.of("orders", "ORDER_LINE"));

  @Test
  void tenantIdReflectsTheAmbientContext() {
    TenantContext.runAs(
        Tenants.of("acme"),
        () -> assertThat(((StringValue) handler.getTenantId()).getValue()).isEqualTo("acme"));
  }

  @Test
  void tenantIdFallsBackToTheRootSentinelWhenNoneBound() {
    assertThat(((StringValue) handler.getTenantId()).getValue()).isEqualTo(Tenants.ROOT.value());
  }

  @Test
  void configuredColumnIsUsed() {
    assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
  }

  @Test
  void onlyConfiguredTablesAreScoped() {
    assertThat(handler.ignoreTable("orders")).isFalse();
    assertThat(handler.ignoreTable("payments")).isTrue();
  }

  @Test
  void tableMatchIsCaseInsensitiveAndIgnoresQuotingAndSchema() {
    assertThat(handler.ignoreTable("ORDERS")).isFalse();
    assertThat(handler.ignoreTable("`orders`")).isFalse();
    assertThat(handler.ignoreTable("\"Orders\"")).isFalse();
    assertThat(handler.ignoreTable("public.orders")).isFalse();
    // configured as ORDER_LINE — matched lower-cased
    assertThat(handler.ignoreTable("order_line")).isFalse();
  }

  @Test
  void anEmptyAllowListIgnoresEverything() {
    TenantContextTenantLineHandler empty =
        new TenantContextTenantLineHandler("tenant_id", List.of());
    assertThat(empty.ignoreTable("orders")).isTrue();
    assertThat(empty.ignoreTable("anything")).isTrue();
  }
}

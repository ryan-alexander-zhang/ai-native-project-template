package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.tenancy.MissingTenantException;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantEnforcement;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.util.List;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTenantLineHandlerTest {

  private static final TenantEnforcement ENFORCEMENT = new TenantEnforcement();

  private final TenantContextTenantLineHandler handler =
      new TenantContextTenantLineHandler("tenant_id", List.of("orders", "ORDER_LINE"));

  @AfterEach
  void tearDown() {
    TenantContext.clear();
    ENFORCEMENT.disable();
  }

  @Test
  void tenantIdReflectsTheAmbientContext() {
    TenantContext.runAs(
        Tenants.of("acme"),
        () -> assertThat(((StringValue) handler.getTenantId()).getValue()).isEqualTo("acme"));
  }

  @Test
  void tenantIdIsTheRootSentinelWhenNoneBoundAndTenancyIsOff() {
    ENFORCEMENT.disable();
    assertThat(((StringValue) handler.getTenantId()).getValue()).isEqualTo(Tenants.ROOT.value());
  }

  @Test
  void rewritingRefusesWhenTenancyIsOnAndNoTenantIsBound() {
    // Narrowing the predicate to the sentinel here is what makes a tenant's rows silently
    // "disappear" from a query (and land in the shared bucket on a write), so the handler must
    // refuse instead of supplying a value nobody chose.
    ENFORCEMENT.enable();
    assertThatThrownBy(handler::getTenantId).isInstanceOf(MissingTenantException.class);
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

  /**
   * A schema-qualified entry scopes exactly one schema's table. A bare entry keeps its historical
   * meaning — it matches the table name in any schema — which is also why two contexts with a
   * same-named table need the qualified form to scope only one of them.
   */
  @Test
  void aSchemaQualifiedEntryScopesOnlyThatSchema() {
    TenantContextTenantLineHandler qualified =
        new TenantContextTenantLineHandler("tenant_id", List.of("ordering.orders"));

    assertThat(qualified.ignoreTable("ordering.orders")).isFalse();
    assertThat(qualified.ignoreTable("\"ordering\".\"Orders\"")).isFalse();
    assertThat(qualified.ignoreTable("inventory.orders")).isTrue();
    // An unqualified reference cannot prove which schema it resolves to, so a qualified entry
    // does not claim it.
    assertThat(qualified.ignoreTable("orders")).isTrue();
  }

  @Test
  void anEmptyAllowListIgnoresEverything() {
    TenantContextTenantLineHandler empty =
        new TenantContextTenantLineHandler("tenant_id", List.of());
    assertThat(empty.ignoreTable("orders")).isTrue();
    assertThat(empty.ignoreTable("anything")).isTrue();
  }
}

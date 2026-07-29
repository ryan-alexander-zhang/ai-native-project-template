package com.aipersimmon.ddd.tenancy.mybatisplus;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/**
 * A {@link TenantLineHandler} that scopes a fixed allow-list of tables to the ambient {@link
 * TenantContext}. The tenant is the current context's value, or the root sentinel when none is
 * bound (single-tenant, or a caller outside a request/command scope). Only the configured tables
 * are rewritten; every other table — the consumer's own, the framework's background-polled ones,
 * shedlock — is ignored, so this never touches a table it was not told owns the discriminator.
 */
public final class TenantContextTenantLineHandler implements TenantLineHandler {

  private final String tenantColumn;
  private final Set<String> tenantTables;

  public TenantContextTenantLineHandler(String tenantColumn, Collection<String> tenantTables) {
    this.tenantColumn = tenantColumn;
    this.tenantTables =
        tenantTables.stream()
            .map(TenantContextTenantLineHandler::normalize)
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Expression getTenantId() {
    return new StringValue(TenantContext.effective().value());
  }

  @Override
  public String getTenantIdColumn() {
    return tenantColumn;
  }

  /**
   * Ignore (do not rewrite) every table that is not in the opt-in set. An empty set therefore makes
   * the interceptor a no-op.
   */
  @Override
  public boolean ignoreTable(String tableName) {
    return !tenantTables.contains(normalize(tableName));
  }

  /** Strip any schema qualifier and identifier quoting, and lower-case, for a stable comparison. */
  private static String normalize(String tableName) {
    String name = tableName.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    int dot = name.lastIndexOf('.');
    if (dot >= 0) {
      name = name.substring(dot + 1);
    }
    return name.trim().toLowerCase(Locale.ROOT);
  }
}

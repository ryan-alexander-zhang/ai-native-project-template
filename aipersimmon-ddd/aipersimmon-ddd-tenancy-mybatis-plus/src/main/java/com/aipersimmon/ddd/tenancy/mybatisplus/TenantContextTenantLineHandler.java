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

  /** Entries without a schema qualifier: match the table name whatever schema it resolves to. */
  private final Set<String> bareTables;

  /**
   * Entries with a schema qualifier ({@code ordering.orders}): match only that schema's table, so
   * two contexts with a same-named table can scope one without scoping the other. They claim only
   * schema-qualified references — an unqualified reference cannot prove which schema it resolves
   * to, and guessing would scope a table nobody opted in.
   */
  private final Set<String> qualifiedTables;

  public TenantContextTenantLineHandler(String tenantColumn, Collection<String> tenantTables) {
    this.tenantColumn = tenantColumn;
    Set<String> cleaned =
        tenantTables.stream()
            .map(TenantContextTenantLineHandler::clean)
            .collect(Collectors.toUnmodifiableSet());
    this.bareTables =
        cleaned.stream()
            .filter(name -> !name.contains("."))
            .collect(Collectors.toUnmodifiableSet());
    this.qualifiedTables =
        cleaned.stream().filter(name -> name.contains(".")).collect(Collectors.toUnmodifiableSet());
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
    String cleaned = clean(tableName);
    if (qualifiedTables.contains(cleaned)) {
      return false;
    }
    int dot = cleaned.lastIndexOf('.');
    String bare = dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    return !bareTables.contains(bare);
  }

  /** Strip identifier quoting and lower-case, keeping any schema qualifier for exact matching. */
  private static String clean(String tableName) {
    return tableName
        .replace("`", "")
        .replace("\"", "")
        .replace("[", "")
        .replace("]", "")
        .trim()
        .toLowerCase(Locale.ROOT);
  }
}

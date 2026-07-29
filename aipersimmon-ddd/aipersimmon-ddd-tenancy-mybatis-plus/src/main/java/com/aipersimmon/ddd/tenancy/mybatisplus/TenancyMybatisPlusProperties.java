package com.aipersimmon.ddd.tenancy.mybatisplus;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the MyBatis-Plus tenant-line interceptor.
 *
 * <p>{@link #getTenantTables()} is the allow-list of tables the interceptor scopes to the ambient
 * tenant; it is <em>empty by default</em> so nothing is rewritten until a consumer opts a table in.
 * This is deliberate: the interceptor is global across the shared {@code SqlSessionFactory}, so a
 * default that touched arbitrary tables would break the consumer's own domain queries and the
 * framework's background-polled tables. Consumers list the domain tables they want auto-scoped.
 */
@ConfigurationProperties("aipersimmon.ddd.tenancy.mybatis-plus")
public class TenancyMybatisPlusProperties {

  /** The tenant discriminator column, matched across all scoped tables. */
  private String tenantColumn = "tenant_id";

  /**
   * Tables the interceptor scopes to the ambient tenant. Empty means the interceptor is a no-op.
   * Only list tables that (a) have the {@link #tenantColumn} and (b) are accessed exclusively under
   * a bound {@code TenantContext} — never a background poller's tenant-less path.
   *
   * <p>Listing a table a poller reads makes every poll fail with {@code MissingTenantException}:
   * with multi-tenancy enabled, rewriting a query for an unbound thread is refused rather than
   * narrowed to the root sentinel. That is deliberate and it is why the framework's own
   * store-and-forward tables are absent from this list — a relay scans them tenant-lessly and each
   * row carries its tenant as a stamped data column, not as a query predicate.
   */
  private List<String> tenantTables = new ArrayList<>();

  public String getTenantColumn() {
    return tenantColumn;
  }

  public void setTenantColumn(String tenantColumn) {
    this.tenantColumn = tenantColumn;
  }

  public List<String> getTenantTables() {
    return tenantTables;
  }

  public void setTenantTables(List<String> tenantTables) {
    this.tenantTables = tenantTables;
  }
}

package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.tenancy.MissingTenantPolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for edge tenant resolution. */
@ConfigurationProperties("aipersimmon.ddd.tenancy")
public class TenancyProperties {

  /**
   * Whether multi-tenancy resolution is active. When {@code false} (the default), no tenant filter
   * or interceptor is wired and commands run under the {@code __root__} sentinel — behaviour
   * identical to before tenancy existed (single-tenant is N=1).
   */
  private boolean enabled = false;

  /** The request header the default resolver reads the tenant from. */
  private String header = "X-Tenant-Id";

  /** What to do when resolution is active but no tenant resolves from a request. */
  private MissingTenantPolicy missingPolicy = MissingTenantPolicy.REJECT;

  /**
   * Request paths (Ant-style patterns) the resolution filter skips entirely — no tenant is resolved
   * or required for them, so they are never rejected under {@link MissingTenantPolicy#REJECT}. The
   * default excludes the actuator base path, because liveness/readiness probes and other management
   * traffic carry no tenant and must stay reachable. Setting this property replaces the default, so
   * re-list {@code /actuator/**} if you add your own (public endpoints, webhooks, docs).
   */
  private List<String> excludePaths = new ArrayList<>(List.of("/actuator/**"));

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getHeader() {
    return header;
  }

  public void setHeader(String header) {
    this.header = header;
  }

  public MissingTenantPolicy getMissingPolicy() {
    return missingPolicy;
  }

  public void setMissingPolicy(MissingTenantPolicy missingPolicy) {
    this.missingPolicy = missingPolicy;
  }

  public List<String> getExcludePaths() {
    return excludePaths;
  }

  public void setExcludePaths(List<String> excludePaths) {
    this.excludePaths = excludePaths;
  }
}

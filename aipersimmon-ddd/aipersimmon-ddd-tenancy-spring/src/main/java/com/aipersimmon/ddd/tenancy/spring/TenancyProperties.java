package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.tenancy.MissingTenantPolicy;
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
}

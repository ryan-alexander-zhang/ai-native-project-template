package com.example.samples.s04;

import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP calls that name a tenant, the way the gateway in front of this service would.
 *
 * <p>The header is {@code X-Tenant-Id} and this service is configured to believe it
 * ({@code trust-header: true}), which is only sound because a real deployment puts something in front
 * that authenticates the caller and <em>rewrites</em> the header. A test is exactly the situation that
 * component is missing from, which is why these helpers can impersonate any tenant with one string —
 * and why the library refuses to trust the header unless a deployment says out loud that it may.
 */
final class TenantRequests {

  static final String TENANT_HEADER = "X-Tenant-Id";

  private TenantRequests() {}

  static ResponseEntity<String> post(TestRestTemplate http, String tenant, Map<String, ?> body) {
    return http.exchange("/orders", HttpMethod.POST, entity(tenant, body), String.class);
  }

  /** A POST with no tenant header at all — what a caller that bypassed the gateway would send. */
  static ResponseEntity<String> postWithoutTenant(TestRestTemplate http, Map<String, ?> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(
        "/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  static ResponseEntity<String> get(TestRestTemplate http, String tenant, String path) {
    return http.exchange(path, HttpMethod.GET, entity(tenant, null), String.class);
  }

  private static HttpEntity<Object> entity(String tenant, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(TENANT_HEADER, tenant);
    return new HttpEntity<>(body, headers);
  }
}

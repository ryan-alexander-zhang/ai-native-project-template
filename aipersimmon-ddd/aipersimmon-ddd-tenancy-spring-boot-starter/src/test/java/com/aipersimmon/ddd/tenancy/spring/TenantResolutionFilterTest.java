package com.aipersimmon.ddd.tenancy.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipersimmon.ddd.tenancy.MissingTenantPolicy;
import com.aipersimmon.ddd.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantResolutionFilterTest {

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final FilterChain chain = mock(FilterChain.class);

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private TenantResolutionFilter filter(MissingTenantPolicy policy) {
    return new TenantResolutionFilter(new HeaderTenantResolver("X-Tenant-Id"), policy);
  }

  private String[] captureTenantDuringChain() throws Exception {
    String[] seen = new String[1];
    doAnswer(
            invocation -> {
              seen[0] = TenantContext.require().value();
              return null;
            })
        .when(chain)
        .doFilter(request, response);
    return seen;
  }

  @Test
  void bindsResolvedTenantDuringTheChainAndClearsAfter() throws Exception {
    when(request.getHeader("X-Tenant-Id")).thenReturn("acme");
    String[] seen = captureTenantDuringChain();

    filter(MissingTenantPolicy.REJECT).doFilterInternal(request, response, chain);

    assertEquals("acme", seen[0]);
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void rejectsWith400WhenNoTenantAndPolicyIsReject() throws Exception {
    when(request.getHeader("X-Tenant-Id")).thenReturn(null);

    filter(MissingTenantPolicy.REJECT).doFilterInternal(request, response, chain);

    verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "missing tenant");
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void fallsBackToRootWhenNoTenantAndPolicyIsSystem() throws Exception {
    when(request.getHeader("X-Tenant-Id")).thenReturn(null);
    String[] seen = captureTenantDuringChain();

    filter(MissingTenantPolicy.SYSTEM).doFilterInternal(request, response, chain);

    assertEquals("__root__", seen[0]);
  }

  @Test
  void skipsExcludedPathsAndFiltersEverythingElse() {
    TenantResolutionFilter filter =
        new TenantResolutionFilter(
            new HeaderTenantResolver("X-Tenant-Id"),
            MissingTenantPolicy.REJECT,
            List.of("/actuator/**"));
    when(request.getContextPath()).thenReturn("");

    when(request.getRequestURI()).thenReturn("/actuator/health");
    assertTrue(filter.shouldNotFilter(request), "actuator path must be excluded");

    when(request.getRequestURI()).thenReturn("/orders");
    assertFalse(filter.shouldNotFilter(request), "a domain path must still be filtered");
  }

  @Test
  void rejectsWith400WhenTenantValueIsInvalid() throws Exception {
    // A reserved-prefix value makes the resolver's Tenants.of throw.
    when(request.getHeader("X-Tenant-Id")).thenReturn("__sneaky");

    filter(MissingTenantPolicy.REJECT).doFilterInternal(request, response, chain);

    verify(response).sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid tenant");
    verify(chain, never()).doFilter(request, response);
  }
}

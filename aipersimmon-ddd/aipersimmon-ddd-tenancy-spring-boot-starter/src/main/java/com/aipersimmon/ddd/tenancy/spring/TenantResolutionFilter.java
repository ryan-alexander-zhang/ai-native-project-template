package com.aipersimmon.ddd.tenancy.spring;

import com.aipersimmon.ddd.tenancy.MissingTenantPolicy;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.TenantResolutionContext;
import com.aipersimmon.ddd.tenancy.TenantResolver;
import com.aipersimmon.ddd.tenancy.Tenants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the tenant at the edge and binds it for the request. Mirrors the request-id filter: it
 * resolves via the {@link TenantResolver}, then binds the tenant into the {@link TenantContext} and
 * the SLF4J {@link MDC} for the duration of the request, always clearing both in a finally block.
 *
 * <p>When no tenant resolves it applies the {@link MissingTenantPolicy}: {@code SYSTEM} falls back
 * to the {@code __root__} sentinel; {@code REJECT} (the default) fails the request with {@code 400
 * Bad Request} rather than letting it read or write a shared bucket. A present-but-invalid tenant
 * value is likewise rejected.
 *
 * <p>Paths matching one of the configured {@code excludePaths} (Ant-style patterns) are skipped
 * entirely — no tenant is resolved or required — so tenant-less management traffic (actuator
 * probes) and any explicitly public endpoint stay reachable under {@code REJECT}.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

  /** MDC key under which the current tenant is stored. */
  public static final String TENANT_MDC_KEY = "tenant";

  private final TenantResolver resolver;
  private final MissingTenantPolicy missingPolicy;
  private final List<String> excludePaths;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public TenantResolutionFilter(TenantResolver resolver, MissingTenantPolicy missingPolicy) {
    this(resolver, missingPolicy, List.of());
  }

  public TenantResolutionFilter(
      TenantResolver resolver, MissingTenantPolicy missingPolicy, Collection<String> excludePaths) {
    this.resolver = resolver;
    this.missingPolicy = missingPolicy;
    this.excludePaths = List.copyOf(excludePaths);
  }

  /**
   * Skip the filter for configured public/management paths (the tenant is neither read nor set).
   *
   * <p>Matches the path the servlet container will dispatch on, not the raw request line. The two
   * differ: a container resolves {@code /actuator/../orders} to {@code /orders} when choosing a
   * handler, while {@code getRequestURI()} still reports the traversal — so matching the raw value
   * would let {@code /actuator/../orders} match an {@code /actuator/**} exclude and reach a
   * business endpoint with no tenant resolved at all. Anything that cannot be reduced to a plain
   * absolute path is treated as not excluded, so a hostile path is resolved (and rejected) rather
   * than skipped.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = dispatchPath(request);
    if (path == null) {
      return false;
    }
    for (String pattern : excludePaths) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The normalized, context-relative path, or {@code null} when the request path is suspicious
   * enough that no exclude should apply: an unresolvable traversal, an encoded separator, or an
   * embedded path parameter, each of which can make one path match two patterns.
   */
  private static String dispatchPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return null;
    }
    String path = uri.substring(request.getContextPath().length());
    // A path parameter (;jsessionid=..., ;/..) is not part of the path the container matches on and
    // is a classic way to smuggle a pattern match; refuse rather than guess.
    if (path.indexOf(';') >= 0) {
      return null;
    }
    // %2F / %5C decode to separators after matching, so a pattern could match a path that the
    // container later resolves elsewhere.
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) {
      return null;
    }
    String normalized = StringUtils.cleanPath(path.replace('\\', '/'));
    // cleanPath leaves leading traversal it cannot resolve ("/../x"), which means the path escapes
    // the context and there is nothing safe to match.
    return normalized.startsWith("/../") || normalized.equals("/..") ? null : normalized;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    TenantId tenant;
    try {
      Optional<TenantId> resolved = resolver.resolve(contextOf(request));
      if (resolved.isPresent()) {
        tenant = resolved.get();
      } else if (missingPolicy == MissingTenantPolicy.SYSTEM) {
        tenant = Tenants.ROOT;
      } else {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "missing tenant");
        return;
      }
    } catch (IllegalArgumentException malformed) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid tenant");
      return;
    }
    TenantContext.set(tenant);
    MDC.put(TENANT_MDC_KEY, tenant.value());
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TENANT_MDC_KEY);
      TenantContext.clear();
    }
  }

  private static TenantResolutionContext contextOf(HttpServletRequest request) {
    return new TenantResolutionContext() {
      @Override
      public Optional<String> header(String name) {
        return Optional.ofNullable(request.getHeader(name)).filter(v -> !v.isBlank());
      }

      @Override
      public Optional<String> host() {
        return Optional.ofNullable(request.getServerName());
      }
    };
  }
}

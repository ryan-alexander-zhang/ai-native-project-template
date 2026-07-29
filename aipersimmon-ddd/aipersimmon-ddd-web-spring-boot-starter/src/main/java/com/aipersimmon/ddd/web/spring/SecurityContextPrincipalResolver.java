package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.spi.IdempotencyPrincipalResolver;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the caller from the Spring Security context, which is where it is by the time this runs —
 * the idempotency filter is ordered after the security filter chain precisely so the principal
 * exists.
 *
 * <p>An unauthenticated request yields empty rather than a placeholder, so keys on a public
 * endpoint are scoped by tenant alone, which is as far as identity goes there. Spring Security's
 * anonymous authentication is treated as unauthenticated for the same reason: every anonymous
 * caller shares that name, so keying on it would put them all in one namespace — the pooling this
 * resolver exists to prevent.
 *
 * <p>The name is the authenticated principal's, which for a token-based setup is usually the
 * subject. Where the meaningful owner is something else — the client the token was issued to rather
 * than the end user acting through it — supply your own {@link IdempotencyPrincipalResolver} bean.
 */
public class SecurityContextPrincipalResolver implements IdempotencyPrincipalResolver {

  private static final String ANONYMOUS_AUTHENTICATION =
      "org.springframework.security.authentication.AnonymousAuthenticationToken";

  @Override
  public Optional<String> currentPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || ANONYMOUS_AUTHENTICATION.equals(authentication.getClass().getName())) {
      return Optional.empty();
    }
    return Optional.ofNullable(authentication.getName()).filter(name -> !name.isBlank());
  }
}

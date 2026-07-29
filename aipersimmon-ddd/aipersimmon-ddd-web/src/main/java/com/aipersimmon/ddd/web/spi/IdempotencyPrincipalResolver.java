package com.aipersimmon.ddd.web.spi;

import java.util.Optional;

/**
 * Names the caller an idempotency key belongs to, so one caller's key cannot address another's
 * stored outcome.
 *
 * <p>This has to be an extension point rather than a fixed lookup: the framework cannot know what a
 * principal is in a given deployment (a token subject, an API-key id, a service account), and
 * getting it wrong is not a cosmetic failure — a key scoped to the wrong thing serves one caller's
 * response body to another. When a Spring Security context is available the starter reads the
 * authenticated name from it; supply your own bean to key on something else, such as the client id
 * a token was issued to rather than the end user acting through it.
 *
 * <p>Returning {@link Optional#empty()} is correct for an endpoint with no authentication at all:
 * keys are then scoped by tenant only, which is as far as identity goes there. It must not be used
 * to skip resolution on an authenticated endpoint.
 */
@FunctionalInterface
public interface IdempotencyPrincipalResolver {

  /** The current caller's stable identifier, or empty when the request is unauthenticated. */
  Optional<String> currentPrincipal();
}

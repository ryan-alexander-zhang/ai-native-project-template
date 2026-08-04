package com.example.samples.s07.payments.infrastructure.gateway;

import com.example.samples.s07.payments.domain.GatewayOutcome;
import java.util.Optional;

/**
 * The code table: the provider's {@code result_code} in, our {@link GatewayOutcome} out.
 *
 * <p>This is the anticorruption layer reduced to its smallest honest form, and it is one class rather
 * than two because both directions of the integration need it — the webhook and the status query answer
 * with the same codes. Duplicating six lines into two adapters is how a provider's new code gets handled
 * on one path and not the other, six months apart.
 *
 * <p><strong>An unmapped code is not a failure.</strong> {@link Optional#empty()} means "we do not know
 * what this means", which every caller here turns into a human's problem. The alternative — a default
 * branch mapping the unknown to {@code FAILED} — is the single most expensive line of code an
 * integration like this can contain: the day the provider introduces a code for a <em>successful</em>
 * charge under a new scheme, every one of them is recorded as a failure, and the customers who were
 * charged are the ones who complain.
 */
final class GatewayResultCodes {

  private GatewayResultCodes() {}

  static Optional<GatewayOutcome> translate(String resultCode) {
    if (resultCode == null) {
      return Optional.empty();
    }
    return switch (resultCode) {
      case "PND" -> Optional.of(GatewayOutcome.ACCEPTED);
      case "00" -> Optional.of(GatewayOutcome.SUCCEEDED);
      // Declines. Enumerated rather than matched by a range, because "5x means declined" is a pattern
      // we inferred and not a contract we were given.
      case "51", "05", "54", "61" -> Optional.of(GatewayOutcome.FAILED);
      default -> Optional.empty();
    };
  }
}

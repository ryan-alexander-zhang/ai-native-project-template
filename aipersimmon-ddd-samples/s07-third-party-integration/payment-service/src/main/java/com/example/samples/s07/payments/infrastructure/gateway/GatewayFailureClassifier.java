package com.example.samples.s07.payments.infrastructure.gateway;

import com.aipersimmon.ddd.outbox.DefaultFailureClassifier;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Teaches the relay which failures of an HTTP transport are hopeless.
 *
 * <p>The library's default is deliberately conservative — retry unless retrying provably cannot help —
 * and its permanent set is about message shape: an unknown {@code (type, version)}, a malformed envelope,
 * a payload that will not parse. That set was written for a broker, where the transport itself does not
 * express opinions about the message. An HTTP provider does: a 400 is the provider saying "this request is
 * wrong", and it will be equally wrong on the tenth attempt an hour from now.
 *
 * <p>So this bean adds one rule and delegates everything else. It replaces the default because the
 * library's bean is {@code @ConditionalOnMissingBean(FailureClassifier.class)}, and it delegates rather
 * than reimplements because the message-shape rules are still right.
 *
 * <p><strong>429 is the exception that proves the rule.</strong> Too Many Requests is a 4xx that means
 * "not now", not "not ever" — treating it as permanent would dead-letter a queue of perfectly good
 * payments the first time a provider throttles us. It is the one client error that stays transient.
 *
 * <p>What "permanent" buys is not the saved attempts, it is the timing: the row lands in the dead-letter
 * table now, where an operator sees it, rather than in ten attempts' time. And a dead letter here is not
 * the end of the payment — the reconciler will find it unsettled and ask the provider, which is how a
 * request the provider never accepted becomes a review item instead of a silent loss.
 */
@Component
class GatewayFailureClassifier implements FailureClassifier {

  private final FailureClassifier delegate = new DefaultFailureClassifier();

  @Override
  public Failure classify(Throwable error) {
    // Bounded like the library's walk, and for the same reason: a cause chain can be circular in more
    // than the one way a self-reference check catches.
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < 20; depth++, cause = nextCause(cause)) {
      if (cause instanceof HttpClientErrorException clientError) {
        return clientError.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
            ? Failure.TRANSIENT
            : Failure.PERMANENT;
      }
    }
    return delegate.classify(error);
  }

  private static Throwable nextCause(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }
}

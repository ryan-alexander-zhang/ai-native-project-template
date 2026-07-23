package com.aipersimmon.ddd.operationlog.spi;

import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import java.util.Objects;

/**
 * What a {@link FailureClassifier} decides from a throwable: the business {@link Outcome} ({@code
 * REJECTED} vs {@code FAILED}) and the sanitized {@link ClassifiedFailure}.
 *
 * <p>It deliberately does <strong>not</strong> carry a {@code Completion}: whether the transaction
 * was {@code NOT_STARTED} (rejected before it began) or {@code ROLLED_BACK} (failed after it began)
 * depends on transaction state, which the interceptor knows and the classifier does not.
 */
public record ClassifiedOutcome(Outcome outcome, ClassifiedFailure failure) {

  /**
   * @throws NullPointerException if either component is null
   */
  public ClassifiedOutcome {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(failure, "failure");
  }

  /** A business rejection with sanitized facts. */
  public static ClassifiedOutcome rejected(String code, String category, String safeSummary) {
    return new ClassifiedOutcome(
        Outcome.REJECTED, new ClassifiedFailure(code, category, safeSummary));
  }

  /** A technical failure with sanitized facts. */
  public static ClassifiedOutcome failed(String code, String category, String safeSummary) {
    return new ClassifiedOutcome(
        Outcome.FAILED, new ClassifiedFailure(code, category, safeSummary));
  }
}

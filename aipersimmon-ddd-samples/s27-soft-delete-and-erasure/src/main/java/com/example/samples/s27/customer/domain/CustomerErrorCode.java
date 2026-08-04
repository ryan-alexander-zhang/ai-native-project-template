package com.example.samples.s27.customer.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** This context's refusals. */
public enum CustomerErrorCode implements ErrorCode {
  CUSTOMER_NOT_FOUND("customer.not-found", ErrorCategory.NOT_FOUND),
  EMAIL_ALREADY_TAKEN("customer.email-already-taken", ErrorCategory.CONFLICT),
  /**
   * The refusal that makes erasure ordered rather than immediate: announcements about this customer are
   * still queued, and erasing now would either send the personal data after it was supposed to be gone or
   * require dropping the announcements and leaving every downstream permanently wrong. See
   * {@code EraseCustomerHandler}.
   */
  ANNOUNCEMENTS_STILL_QUEUED("customer.announcements-still-queued", ErrorCategory.CONFLICT);

  private final String code;
  private final ErrorCategory category;

  CustomerErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}

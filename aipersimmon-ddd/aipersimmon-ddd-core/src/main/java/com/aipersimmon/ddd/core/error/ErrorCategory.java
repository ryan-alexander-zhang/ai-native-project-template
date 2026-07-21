package com.aipersimmon.ddd.core.error;

/**
 * A coarse, transport-neutral classification of an {@link ErrorCode}. It exists so a consumer that
 * has not registered a richer mapping for a specific code can still derive a sensible default (for
 * example, an interface layer choosing an HTTP status) from the category alone. It deliberately
 * does not mention any transport or status code — that translation belongs to the outer layers.
 */
public enum ErrorCategory {

  /** A domain rule or invariant was violated. */
  DOMAIN_RULE,

  /** A referenced aggregate or resource does not exist. */
  NOT_FOUND,

  /** The request conflicts with the current state (e.g. a concurrency clash). */
  CONFLICT,

  /** Input failed validation. */
  VALIDATION,

  /** The caller is not authenticated. */
  UNAUTHORIZED,

  /** The caller is authenticated but not permitted. */
  FORBIDDEN,

  /** An unexpected technical fault. */
  UNEXPECTED
}

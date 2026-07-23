package com.aipersimmon.ddd.operationlog.port;

/**
 * An opaque forward cursor over the {@code (occurred_at, record_id)} ordering. {@code token} is
 * null for the first page; a backend encodes/decodes its own token.
 */
public record OperationLogCursor(String token) {

  /** The first page (no token). */
  public static OperationLogCursor start() {
    return new OperationLogCursor(null);
  }

  /** A cursor from a previously returned token. */
  public static OperationLogCursor of(String token) {
    return new OperationLogCursor(token);
  }
}

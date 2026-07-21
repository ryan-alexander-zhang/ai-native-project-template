package com.aipersimmon.ddd.web.page;

/**
 * An opaque cursor: a pointer to a position in a result set that the client echoes back verbatim to
 * fetch the next page. Its {@link #value()} is deliberately unstructured — clients must not inspect
 * or construct it, so the server can change its encoding without breaking callers.
 *
 * @param value the opaque token (never blank)
 */
public record Cursor(String value) {

  public Cursor {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("cursor value must not be blank");
    }
  }

  public static Cursor of(String value) {
    return new Cursor(value);
  }
}

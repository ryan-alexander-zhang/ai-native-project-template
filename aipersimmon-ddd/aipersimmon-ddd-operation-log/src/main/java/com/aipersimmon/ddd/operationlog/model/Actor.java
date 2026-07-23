package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * A trusted snapshot of who performed an operation, captured at record time from a trusted
 * boundary. Never derived from command payload. {@code displayName} is subject to redaction before
 * it is frozen into an entry.
 */
@ValueObject
public record Actor(String type, String id, String displayName) {

  /**
   * @throws NullPointerException if {@code type} is null
   */
  public Actor {
    Objects.requireNonNull(type, "type");
  }

  /** A human end-user actor. */
  public static Actor user(String id, String displayName) {
    return new Actor("USER", id, displayName);
  }

  /** A system/background actor (scheduler, relay), with no human identity. */
  public static Actor system(String id) {
    return new Actor("SYSTEM", id, id);
  }

  /** A service/automation actor (CLI, ops tooling). */
  public static Actor service(String id) {
    return new Actor("SERVICE", id, id);
  }
}

package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * What the job produced, <strong>by reference</strong>.
 *
 * <p>The bytes are not here and must never be. A month of settlement rows is tens of megabytes; putting it
 * in the aggregate would put it in the row, in the optimistic-lock update, and in every read of the job's
 * status. The same rule the library states for a process manager's payloads — "keep payloads minimal and
 * store large artifacts by reference" — and it is not really about process managers: it is about anything
 * that is written transactionally.
 *
 * <p>{@code rowCount} and {@code bytes} are here because they are the only claim about the file that the
 * job itself can make, and a caller downloading 0 bytes wants to know whether that is the file or the
 * plumbing.
 *
 * @param path where the bytes are; opaque to the domain, resolved by the artifact store
 * @param bytes the size the writer observed
 * @param rowCount how many source rows went in
 */
@ValueObject
public record Artifact(String path, long bytes, long rowCount) {

  public Artifact {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("artifact path required");
    }
    if (bytes < 0) {
      throw new IllegalArgumentException("artifact bytes must not be negative");
    }
    if (rowCount < 0) {
      throw new IllegalArgumentException("artifact row count must not be negative");
    }
  }
}

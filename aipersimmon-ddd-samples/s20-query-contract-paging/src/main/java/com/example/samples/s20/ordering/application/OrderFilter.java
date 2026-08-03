package com.example.samples.s20.ordering.application;

import com.example.samples.s20.ordering.domain.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Which orders the client is asking about. A value, not a string of SQL.
 *
 * <p>Two absent fields mean "no restriction", so the same record serves the unfiltered list. Every
 * filter this endpoint supports is a component here, which is what keeps the adapter from growing a
 * {@code where} parameter and the repository from growing a query-string parser: adding a filter is
 * a component plus one conditional predicate, and the set of supported filters is readable in one
 * place.
 *
 * @param customerId only this customer's orders, or null for every customer
 * @param status only orders in this state, or null for every state
 */
public record OrderFilter(String customerId, OrderStatus status) {

  /** No restriction. */
  public static OrderFilter unfiltered() {
    return new OrderFilter(null, null);
  }

  /**
   * A short, stable digest of this filter, carried inside the cursor so a page token cannot be
   * replayed against a different question.
   *
   * <p>Deliberately not {@code hashCode()}: a record's hash is stable within one JVM run and no
   * further, and a cursor outlives a deploy — the client holding one across a restart would get its
   * next page refused for no reason a log would explain. A digest of the canonical form is stable
   * everywhere and forever, which is the property a wire value needs.
   */
  public String fingerprint() {
    String canonical =
        (customerId == null ? "" : customerId)
            + "\n"
            + (status == null ? "" : status.name());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }
}

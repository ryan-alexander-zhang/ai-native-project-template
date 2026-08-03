package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.example.samples.s20.ordering.domain.OrderingErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * What the opaque {@link Cursor} contains once it is opened, and the only place that opens it.
 *
 * <p>The library's {@code Cursor} is a single unstructured string on purpose: "clients must not
 * inspect or construct it, so the server can change its encoding without breaking callers". That is
 * a promise about the wire, and it costs nothing here — the four fields below can grow a fifth
 * tomorrow because no client ever parsed the four.
 *
 * <p>The cursor carries the whole sort key ({@code placedAt} and {@code id}), not just the id: the
 * seek predicate compares against the key it ordered by, and a cursor that named only the id could
 * not express "after this row" under an ordering the id does not define.
 *
 * <p>It also carries <em>which question it answers</em> — the sort and a digest of the filter. A
 * token minted while paging one customer's orders, replayed against another filter, describes a
 * position in a result set that no longer exists; honouring it silently returns a page that is
 * neither the first nor the next. Refusing it is the only answer that is not wrong.
 *
 * <p>Encoding is Base64url, and that is <em>not</em> encryption. Anyone can decode it. Opacity here
 * buys the freedom to change the format, not confidentiality — so nothing secret goes in, and a
 * cursor that must not be forgeable needs a signature (the shape S2 builds for request bodies).
 */
public record PageCursor(OrderSort sort, String queryFingerprint, Instant placedAt, String orderId) {

  private static final String SEPARATOR = "|";
  private static final int FIELDS = 4;

  /** The wire form: one token, no structure a client could rely on. */
  public Cursor encode() {
    String payload =
        String.join(
            SEPARATOR,
            sort.name(),
            queryFingerprint,
            Long.toString(micros(placedAt)),
            orderId);
    return Cursor.of(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Opens a token and checks it belongs to the question being asked.
   *
   * <p>Every failure here is a {@code VALIDATION} error, so it renders as 400 rather than the 500 a
   * bare {@code NumberFormatException} would produce. A read-side contract has failure modes; a
   * client that mangles a cursor deserves to be told so.
   */
  public static PageCursor decode(Cursor cursor, OrderSort sort, String queryFingerprint) {
    String payload;
    try {
      payload = new String(Base64.getUrlDecoder().decode(cursor.value()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw malformed("the cursor is not a valid token", e);
    }
    String[] fields = payload.split("\\" + SEPARATOR, -1);
    if (fields.length != FIELDS) {
      throw malformed("the cursor has " + fields.length + " fields, expected " + FIELDS, null);
    }
    OrderSort cursorSort;
    Instant placedAt;
    try {
      cursorSort = OrderSort.valueOf(fields[0]);
      placedAt = Instant.EPOCH.plus(Long.parseLong(fields[2]), ChronoUnit.MICROS);
    } catch (IllegalArgumentException e) {
      throw malformed("the cursor's contents are not readable", e);
    }
    if (fields[3].isBlank()) {
      throw malformed("the cursor names no row", null);
    }
    if (cursorSort != sort) {
      throw mismatch("this cursor was issued for " + cursorSort + ", the request asks for " + sort);
    }
    if (!fields[1].equals(queryFingerprint)) {
      throw mismatch("this cursor was issued for a different filter");
    }
    return new PageCursor(cursorSort, fields[1], placedAt, fields[3]);
  }

  /**
   * The sort key's leading component, at the precision the column stores.
   *
   * <p>{@code timestamptz} keeps microseconds. Round-tripping the key through milliseconds would
   * move the cursor slightly off the row it names, and the row on the boundary would be returned
   * twice or never — a bug that appears only when two orders land in the same millisecond, which is
   * to say only in production.
   */
  private static long micros(Instant instant) {
    return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
  }

  private static ApplicationException malformed(String detail, Throwable cause) {
    return new ApplicationException(OrderingErrorCode.MALFORMED_CURSOR, detail, cause);
  }

  private static ApplicationException mismatch(String detail) {
    return new ApplicationException(OrderingErrorCode.CURSOR_DOES_NOT_MATCH_QUERY, detail);
  }
}

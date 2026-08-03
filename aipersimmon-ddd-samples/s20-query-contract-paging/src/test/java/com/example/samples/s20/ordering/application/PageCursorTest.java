package com.example.samples.s20.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.example.samples.s20.ordering.domain.OrderStatus;
import com.example.samples.s20.ordering.domain.OrderingErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The cursor, tested where it is cheapest to test: no Spring, no database. Everything here is a
 * property of the token itself.
 */
class PageCursorTest {

  private static final Instant PLACED_AT = Instant.parse("2026-08-03T10:15:30.123456Z");
  private static final String ORDER_ID = "019186c1-7a3f-7000-8000-0123456789ab";

  private final String fingerprint = new OrderFilter("alice", OrderStatus.PLACED).fingerprint();

  @Test
  void aTokenRoundTripsThroughTheWire() {
    PageCursor original =
        new PageCursor(OrderSort.NEWEST_FIRST, fingerprint, PLACED_AT, ORDER_ID);

    PageCursor decoded =
        PageCursor.decode(original.encode(), OrderSort.NEWEST_FIRST, fingerprint);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void theTokenShowsTheClientNothingItCouldRelyOn() {
    Cursor cursor =
        new PageCursor(OrderSort.NEWEST_FIRST, fingerprint, PLACED_AT, ORDER_ID).encode();

    // Opaque, not encrypted: anyone can decode it, and that is fine. What matters is that no client
    // can read a field out of it by pattern-matching, so the format stays free to change.
    assertThat(cursor.value()).doesNotContain(ORDER_ID).doesNotContain("NEWEST_FIRST");
  }

  @Test
  void theSortKeyKeepsTheColumnsPrecisionAndNoMore() {
    // timestamptz stores microseconds, so that is what the cursor carries. A nanosecond in an
    // Instant that never reached the column has nothing to be compared against.
    Instant withNanos = Instant.parse("2026-08-03T10:15:30.123456789Z");

    PageCursor decoded =
        PageCursor.decode(
            new PageCursor(OrderSort.NEWEST_FIRST, fingerprint, withNanos, ORDER_ID).encode(),
            OrderSort.NEWEST_FIRST,
            fingerprint);

    assertThat(decoded.placedAt()).isEqualTo(Instant.parse("2026-08-03T10:15:30.123456Z"));
  }

  @Test
  void ahandWrittenTokenIsRefusedAsAValidationFailure() {
    assertThatThrownBy(
            () -> PageCursor.decode(Cursor.of("page-2"), OrderSort.NEWEST_FIRST, fingerprint))
        .isInstanceOf(ApplicationException.class)
        .extracting(thrown -> ((ApplicationException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.MALFORMED_CURSOR));
  }

  @Test
  void atruncatedTokenIsRefusedRatherThanGuessedAt() {
    String threeFields =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("NEWEST_FIRST|abc|123".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () -> PageCursor.decode(Cursor.of(threeFields), OrderSort.NEWEST_FIRST, fingerprint))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("3 fields");
  }

  @Test
  void atokenIssuedForAnotherFilterIsRefused() {
    Cursor issuedForAlice =
        new PageCursor(OrderSort.NEWEST_FIRST, fingerprint, PLACED_AT, ORDER_ID).encode();
    String otherFingerprint = new OrderFilter("bob", OrderStatus.PLACED).fingerprint();

    assertThatThrownBy(
            () -> PageCursor.decode(issuedForAlice, OrderSort.NEWEST_FIRST, otherFingerprint))
        .isInstanceOf(ApplicationException.class)
        .extracting(thrown -> ((ApplicationException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.CURSOR_DOES_NOT_MATCH_QUERY));
  }

  @Test
  void atokenIssuedForAnotherOrderingIsRefused() {
    Cursor issuedNewestFirst =
        new PageCursor(OrderSort.NEWEST_FIRST, fingerprint, PLACED_AT, ORDER_ID).encode();

    // Honouring it would seek "after" a row using a comparison the token was not built for, and the
    // page returned would be neither the first nor the next.
    assertThatThrownBy(
            () -> PageCursor.decode(issuedNewestFirst, OrderSort.OLDEST_FIRST, fingerprint))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("OLDEST_FIRST");
  }

  @Test
  void afiltersFingerprintDoesNotDependOnThisJvm() {
    // The digest is over the canonical form, not the record's hashCode, so a cursor minted before a
    // deploy still matches its filter after one. Pinning the value is what would catch a change of
    // algorithm — a change that would refuse every cursor issued by the previous release.
    assertThat(new OrderFilter("alice", OrderStatus.PLACED).fingerprint())
        .isEqualTo("a36820f17c9c");
    assertThat(OrderFilter.unfiltered().fingerprint()).isEqualTo("01ba4719c80b");
  }
}

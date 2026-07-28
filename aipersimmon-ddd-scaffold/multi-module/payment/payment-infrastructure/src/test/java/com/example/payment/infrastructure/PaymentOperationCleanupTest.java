package com.example.payment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The cutoff {@link PaymentOperationCleanup} hands the mapper. Small, and the reason it exists is
 * the sign: a window applied the wrong way round would delete every recent operation and keep the
 * expired ones, which is the precise inverse of what a dedupe log needs and would show up as double
 * authorizations rather than as an error.
 *
 * <p>A stub mapper rather than a database — what is under test is the arithmetic, and the delete
 * itself is one statement MyBatis writes.
 */
class PaymentOperationCleanupTest {

  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
  private static final long THIRTY_DAYS = 2_592_000L;

  private final RecordingMapper mapper = new RecordingMapper();

  @Test
  void deletesRowsOlderThanTheRetentionWindowAndNothingNewer() {
    new PaymentOperationCleanup(mapper, Clock.fixed(NOW, ZoneOffset.UTC), THIRTY_DAYS).purge();

    assertEquals(
        Instant.parse("2026-06-28T12:00:00Z"),
        mapper.cutoff,
        "the cutoff is thirty days before now; rows recorded after it are still live keys");
  }

  private static final class RecordingMapper implements PaymentOperationMapper {
    private Instant cutoff;

    @Override
    public PaymentOperationRow find(String tenantId, String operationId) {
      throw new UnsupportedOperationException("not part of cleanup");
    }

    @Override
    public void record(
        String tenantId,
        String operationId,
        String outcome,
        String declineCode,
        String declineReason) {
      throw new UnsupportedOperationException("not part of cleanup");
    }

    @Override
    public int deleteRecordedBefore(Instant cutoff) {
      this.cutoff = cutoff;
      return 0;
    }
  }
}

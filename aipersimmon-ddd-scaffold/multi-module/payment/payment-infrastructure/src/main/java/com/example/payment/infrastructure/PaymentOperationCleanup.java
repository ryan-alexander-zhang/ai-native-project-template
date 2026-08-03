package com.example.payment.infrastructure;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes {@code payment_operations} rows past their retention window.
 *
 * <p>The table is append-only and grows at the rate orders are paid for, so it needs a retention
 * decision the way the outbox and the inbox do. It did not get one when it was introduced, and the
 * reason is worth keeping: it replaced a {@code ConcurrentHashMap}, which had a retention policy
 * nobody had ever written down — the process restarts and the map is empty. Swapping the storage
 * removed that policy silently, because an unstated policy is invisible at the moment it is taken
 * away.
 *
 * <p>The window has to be chosen the same way the inbox's is, and for the same reason: this is a
 * dedupe log, so once a key is gone a late redelivery of that operation is no longer recognised and
 * would authorize a second time. It must therefore outlast the longest redelivery the broker can
 * produce. The key differs — a business {@code paymentOperationId} rather than a transport message
 * id — but the arithmetic behind the number does not, which is why the configuration points at the
 * inbox's reasoning rather than restating it.
 *
 * <p>Not lock-guarded, matching the framework's own cleanups: the delete is a single cutoff-bounded
 * statement, so several instances running it at once is redundant but harmless. Registered by the
 * composition root rather than annotated here, because whether to delete data at all is a
 * deployment decision, not a property of this adapter.
 */
public class PaymentOperationCleanup {

  private static final Logger log = LoggerFactory.getLogger(PaymentOperationCleanup.class);

  private final PaymentOperationMapper operations;
  private final Clock clock;
  private final long retentionSeconds;

  public PaymentOperationCleanup(
      PaymentOperationMapper operations, Clock clock, long retentionSeconds) {
    this.operations = operations;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
  }

  @Scheduled(fixedDelayString = "${payment.operations.cleanup.poll-delay-ms:3600000}")
  public void purge() {
    int deleted = operations.purgeRecordedBefore(clock.instant().minusSeconds(retentionSeconds));
    if (deleted > 0) {
      log.info(
          "payment operation cleanup removed {} rows older than {}s", deleted, retentionSeconds);
    }
  }
}

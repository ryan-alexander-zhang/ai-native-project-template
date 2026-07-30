package com.aipersimmon.ddd.processmanager.engine.cleanup;

import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes finished process instances whose retention has elapsed, so the four tables stop growing
 * for the lifetime of the deployment.
 *
 * <p>Opt-in, like the outbox purge and for the same reason: deleting data and choosing how long to
 * keep it are deployment decisions, not a framework default. Until it is switched on the tables
 * grow — which is the state this was written to end, and is still better than a library quietly
 * removing a business record somebody expected to find.
 *
 * <p>Each run takes at most one batch, so the delete holds locks for a bounded time on a table the
 * relay and deadline worker are also reading. When a run fills its batch it says so, because a
 * purge that is silently only keeping up looks exactly like a purge that has finished.
 *
 * <p>No cross-instance lock, which matches how everything else in this module coordinates: the
 * relay and deadline worker rely on the lease in their claim and the parked-input worker on the
 * idempotence of a replay. Two instances purging at once select overlapping ids and both issue the
 * deletes; the second removes nothing. That costs a little duplicated work once an hour and cannot
 * corrupt anything — cheaper than a lock whose expiry becomes another thing to reason about.
 */
public class ProcessCleanup {

  private static final Logger log = LoggerFactory.getLogger(ProcessCleanup.class);

  private final ProcessRetentionStore store;
  private final ProcessUnitOfWork unitOfWork;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public ProcessCleanup(
      ProcessRetentionStore store,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      long retentionSeconds,
      int batchSize) {
    this.store = store;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  /**
   * Purge one batch of eligible instances.
   *
   * @return the number of instances removed
   */
  public int purge() {
    return unitOfWork.execute(
        () -> {
          // Selecting and deleting in one transaction is the point: between the two, a relay could
          // otherwise stage an effect on an instance this run had already judged finished, and the
          // delete would take it with the instance.
          List<ProcessInstanceId> purgeable =
              store.findPurgeable(clock.instant().minusSeconds(retentionSeconds), batchSize);
          if (purgeable.isEmpty()) {
            return 0;
          }
          int removed = store.purge(purgeable);
          if (purgeable.size() >= batchSize) {
            log.info(
                "process-manager cleanup removed {} finished instance(s) older than {}s and filled"
                    + " its batch of {}; more remain and the next run will take them",
                removed,
                retentionSeconds,
                batchSize);
          } else {
            log.info(
                "process-manager cleanup removed {} finished instance(s) older than {}s",
                removed,
                retentionSeconds);
          }
          return removed;
        });
  }
}

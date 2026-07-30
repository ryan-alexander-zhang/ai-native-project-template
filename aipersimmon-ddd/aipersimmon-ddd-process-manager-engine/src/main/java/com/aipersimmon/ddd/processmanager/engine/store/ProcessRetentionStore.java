package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;
import java.util.List;

/**
 * Removes finished process instances and everything recorded about them, so the four tables do not
 * grow for the lifetime of the deployment.
 *
 * <p><strong>The unit is the whole instance, never a single row.</strong> An instance's snapshot,
 * its transition log, its effects and its deadlines are one record; deleting a transition while
 * keeping the instance would leave a row whose timeline is a lie and whose "latest transition"
 * lookup finds nothing — a state the runtime refuses to answer about at all.
 *
 * <p>What makes an instance purgeable is deliberately narrower than "it ended". Two states look
 * finished and are not:
 *
 * <ul>
 *   <li>an <strong>undelivered effect</strong>. A terminal decision cancels timers but its staged
 *       effects still go out — the final event of a flow is exactly such an effect — so an ended
 *       instance may still have work in flight.
 *   <li>a <strong>DEAD effect or deadline</strong>. That is the record of a side effect that never
 *       landed, and an operator can still redrive it. Deleting it destroys the evidence that
 *       something was owed, which is the opposite of what a retention policy is for. Dead work is
 *       kept for the same reason the outbox purge leaves the dead-letter table alone.
 * </ul>
 *
 * <p>So an instance goes only when it has ended, its retention has elapsed, and every effect and
 * deadline it holds has settled as delivered, fired, or cancelled.
 *
 * <p>Like {@code claimDue}, the eligibility predicate is one idea expressed as SQL in each backend
 * rather than shared code. That duplication is not an oversight: the predicate <em>is</em> the
 * policy, and it has to be readable next to the statement that acts on it.
 */
public interface ProcessRetentionStore {

  /**
   * Instances that have ended before {@code endedBefore} and hold no unsettled or dead work, at
   * most {@code limit} of them.
   *
   * @return the ids, oldest first
   */
  List<ProcessInstanceId> findPurgeable(Instant endedBefore, int limit);

  /**
   * Delete these instances and every transition, effect and deadline belonging to them.
   *
   * <p>Called in one transaction with the ids from {@link #findPurgeable}, so a crash mid-purge
   * leaves the record whole rather than half-deleted.
   *
   * @return the number of instance rows removed
   */
  int purge(List<ProcessInstanceId> instanceIds);
}

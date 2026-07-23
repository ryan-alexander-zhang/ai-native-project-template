package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists staged effects and drives their delivery lifecycle. Effects are written {@code PENDING}
 * and immediately due in the advance transaction; a relay claims and delivers them afterwards. The
 * mark/retry/dead/cancel transitions are fenced by the lease token so only the current owner can
 * complete a claimed effect.
 */
public interface ProcessEffectStore {

  long nextSeq(ProcessInstanceId instanceId);

  void insert(ProcessEffectInsert effect, Instant now);

  Optional<ClaimedEffect> load(String effectId);

  int markDelivered(String effectId, String leaseToken, Instant now);

  int scheduleRetry(
      String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now);

  int markDead(String effectId, String leaseToken, String error, Instant now);

  int markCancelled(String effectId, String leaseToken, Instant now);

  int redrive(String effectId, Instant now);

  int cancelPending(ProcessInstanceId instanceId, Instant now);

  long countDead();

  long countDead(ProcessInstanceId instanceId);

  Optional<Instant> oldestDuePending(Instant now);

  List<ProcessEffectView> byStatus(EffectStatus status, int limit);
}

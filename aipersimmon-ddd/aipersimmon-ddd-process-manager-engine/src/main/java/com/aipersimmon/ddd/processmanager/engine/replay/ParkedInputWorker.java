package com.aipersimmon.ddd.processmanager.engine.replay;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.runtime.ProcessUnitOfWork;
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInput;
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInputs;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands inputs that were parked during a suspension back to the runtime, once the instance is
 * active again.
 *
 * <p>Parking is what keeps a suspended instance from rebounding inputs at the message layer
 * forever; the debt it creates — one replay owed per parked row — is durable, recorded by {@code
 * replayed_at IS NULL} on the row itself, and drained here rather than inside whatever call resumed
 * the instance. That is the difference between recoverable and lost: the resume commits on its own,
 * and if this worker's node dies mid-drain, the queue still says what is owed and the next poll (on
 * any node) picks it up. Nothing needs the operator to notice.
 *
 * <p>Ordering is the contract parking promises: an instance's inputs replay in arrival order
 * ({@code transition_seq}), so a failure stops that instance's drain rather than letting the next
 * input overtake it. Two nodes draining the same instance is safe without a lease — each replay is
 * deduped by {@code UNIQUE(instance_id, input_message_id)} on its replay transition, and neither
 * node can reach input k+1 before k's replay has committed, so the order survives the overlap.
 *
 * <p>Replay is at-least-once, like every other durable hop here: a crash between the advance and
 * the marker replays the input again, and the runtime answers with a duplicate no-op.
 *
 * <p>An input the definition cannot digest would otherwise be retried at every poll forever, so an
 * exception suspends the instance with {@code suspensionSource=PARKED_INPUT} — the same shape as an
 * effect or deadline exhausting its retries, and the same recovery: it stops being polled, it shows
 * up in the suspended-instance SLI, and an operator redrive resumes it.
 */
public final class ParkedInputWorker {

  private static final Logger log = LoggerFactory.getLogger(ParkedInputWorker.class);
  private static final int MAX_REASON_LENGTH = 512;

  private final ProcessInstanceStore instances;
  private final ProcessTransitionStore transitions;
  private final ProcessPayloadCodecRegistry payloadCodecs;
  private final ProcessRuntime runtime;
  private final ProcessUnitOfWork unitOfWork;
  private final Clock clock;
  private final int batchSize;

  public ParkedInputWorker(
      ProcessInstanceStore instances,
      ProcessTransitionStore transitions,
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessRuntime runtime,
      ProcessUnitOfWork unitOfWork,
      Clock clock,
      int batchSize) {
    this.instances = instances;
    this.transitions = transitions;
    this.payloadCodecs = payloadCodecs;
    this.runtime = runtime;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  /** Drain the parked-input queue of up to {@code batchSize} instances; returns inputs replayed. */
  public int pollOnce() {
    List<ProcessInstanceId> owed =
        unitOfWork.execute(() -> transitions.findInstancesOwedParkedReplay(batchSize));
    int replayed = 0;
    for (ProcessInstanceId instanceId : owed) {
      replayed += drain(instanceId);
    }
    return replayed;
  }

  private int drain(ProcessInstanceId instanceId) {
    ProcessInstanceRow row = unitOfWork.execute(() -> instances.find(instanceId).orElse(null));
    if (row == null || !row.lifecycle().isActive()) {
      return 0; // suspended or ended again between the scan and now; the debt stays recorded
    }
    int replayed = 0;
    for (ParkedInput parked :
        unitOfWork.execute(() -> transitions.findUnreplayedParkedInputs(instanceId))) {
      if (!replay(row.ref(), row.lifecycle(), parked)) {
        return replayed;
      }
      replayed++;
    }
    return replayed;
  }

  /**
   * Replay one parked input and settle its debt.
   *
   * @return whether the drain of this instance may continue to the next input
   */
  private boolean replay(ProcessRef ref, ProcessLifecycle lifecycle, ParkedInput parked) {
    ProcessAdvanceResult result;
    try {
      result =
          TenantContext.runAs(Tenants.fromValue(parked.tenantId()), () -> advance(ref, parked));
    } catch (RuntimeException failure) {
      suspend(ref, lifecycle, parked, failure);
      return false;
    }
    if (result.lifecycle() == ProcessLifecycle.SUSPENDED) {
      // The instance suspended again before this replay landed, so the input was not consumed: the
      // runtime declined to park a replay and the row is still owed. Leave it, and stop here so a
      // later input cannot be replayed ahead of it.
      return false;
    }
    transitions.markParkedReplayed(ref.instanceId(), parked.inputMessageId(), clock.instant());
    return true;
  }

  private ProcessAdvanceResult advance(ProcessRef ref, ParkedInput parked) {
    ProcessPayloadCodec<?> codec = payloadCodecs.forType(parked.inputType());
    ProcessInput input =
        (ProcessInput) codec.decode(new EncodedPayload(parked.inputType(), parked.inputPayload()));
    String replayId = ParkedInputs.replayIdFor(parked.inputMessageId());
    // Replay under the parked input's original correlation so the resumed work stays on the same
    // causal chain, with the parked input as its cause.
    String correlationId = parked.correlationId() != null ? parked.correlationId() : replayId;
    return runtime.handle(
        ref,
        input,
        new CommandContext(parked.tenantId(), replayId, correlationId, parked.inputMessageId()));
  }

  private void suspend(
      ProcessRef ref, ProcessLifecycle lifecycle, ParkedInput parked, RuntimeException failure) {
    log.warn(
        "replay of parked input {} on instance {} failed; suspending the instance for operator"
            + " recovery",
        parked.inputMessageId(),
        ref.instanceId().value(),
        failure);
    unitOfWork.execute(
        () -> {
          Optional<ProcessInstanceRow> current = instances.findForUpdate(ref.instanceId());
          if (current.isPresent() && current.get().lifecycle().canSuspend()) {
            instances.suspend(
                ref.instanceId(),
                lifecycle,
                reason(parked, failure),
                "PARKED_INPUT",
                parked.inputMessageId(),
                clock.instant());
          }
          return null;
        });
  }

  private static String reason(ParkedInput parked, Throwable failure) {
    String reason =
        "replay of parked input "
            + parked.inputMessageId()
            + " failed: "
            + failure.getClass().getName()
            + ": "
            + failure.getMessage();
    return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
  }
}

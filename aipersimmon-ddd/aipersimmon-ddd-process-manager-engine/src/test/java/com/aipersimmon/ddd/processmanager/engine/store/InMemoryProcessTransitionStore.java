package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link ProcessTransitionStore} over a map.
 *
 * <p>Two things are kept exactly as the SQL has them, because the runtime's central promise rests
 * on them. <strong>The dedup key</strong>: {@code UNIQUE(instance_id, input_message_id)} is what
 * makes an input apply exactly once, so a second append under the same input raises {@link
 * ConcurrentTransitionException} here just as a unique-index violation does there — a double that
 * quietly accepted it would let every idempotency test pass without an idempotency mechanism.
 * <strong>The per-instance sequence</strong>: {@code transition_seq} is assigned monotonically per
 * instance and is the order that the timeline, the "latest transition" lookup, and the parked-input
 * replay queue all read by; a map's insertion order would agree with it by accident and stop
 * agreeing the moment a test interleaves two instances.
 *
 * <p>{@code replayed_at} is the one field written after insert, so it is the only mutable one here.
 */
public final class InMemoryProcessTransitionStore implements ProcessTransitionStore, Snapshottable {

  /** One row of {@code aipersimmon_process_transition}. */
  public static final class Row {
    private final String tenantId;
    private final String transitionId;
    private final ProcessInstanceId instanceId;
    private final long seq;
    private final String inputMessageId;
    private final PayloadType inputType;
    private final byte[] inputPayload;
    private final Optional<ProcessLifecycle> fromLifecycle;
    private final ProcessLifecycle toLifecycle;
    private final Optional<ProcessStep> fromStep;
    private final ProcessStep toStep;
    private final DecisionCode decisionCode;
    private final String transitionKind;
    private final String correlationId;
    private final Optional<String> operator;
    private final Optional<String> reason;
    private final Instant createdAt;
    private Instant replayedAt;

    private Row(
        String tenantId,
        String transitionId,
        ProcessInstanceId instanceId,
        long seq,
        String inputMessageId,
        PayloadType inputType,
        byte[] inputPayload,
        Optional<ProcessLifecycle> fromLifecycle,
        ProcessLifecycle toLifecycle,
        Optional<ProcessStep> fromStep,
        ProcessStep toStep,
        DecisionCode decisionCode,
        String transitionKind,
        String correlationId,
        Optional<String> operator,
        Optional<String> reason,
        Instant createdAt) {
      this.tenantId = tenantId;
      this.transitionId = transitionId;
      this.instanceId = instanceId;
      this.seq = seq;
      this.inputMessageId = inputMessageId;
      this.inputType = inputType;
      this.inputPayload = inputPayload.clone();
      this.fromLifecycle = fromLifecycle;
      this.toLifecycle = toLifecycle;
      this.fromStep = fromStep;
      this.toStep = toStep;
      this.decisionCode = decisionCode;
      this.transitionKind = transitionKind;
      this.correlationId = correlationId;
      this.operator = operator;
      this.reason = reason;
      this.createdAt = createdAt;
    }

    public String transitionId() {
      return transitionId;
    }

    public long seq() {
      return seq;
    }

    public String inputMessageId() {
      return inputMessageId;
    }

    public Optional<ProcessLifecycle> fromLifecycle() {
      return fromLifecycle;
    }

    public ProcessLifecycle toLifecycle() {
      return toLifecycle;
    }

    public ProcessStep toStep() {
      return toStep;
    }

    public DecisionCode decisionCode() {
      return decisionCode;
    }

    public String transitionKind() {
      return transitionKind;
    }

    public Optional<String> operator() {
      return operator;
    }

    public Optional<String> reason() {
      return reason;
    }

    public String correlationId() {
      return correlationId;
    }

    public Instant replayedAt() {
      return replayedAt;
    }
  }

  private final Map<String, Row> rows = new LinkedHashMap<>();
  private final Set<String> committedElsewhere = new HashSet<>();
  private final ProcessInstanceStore instances;

  /**
   * The instance store is a collaborator because one query needs it: the replay worklist joins the
   * instance table to skip instances that are suspended or ended.
   */
  public InMemoryProcessTransitionStore(ProcessInstanceStore instances) {
    this.instances = instances;
  }

  private long nextSeq(ProcessInstanceId instanceId) {
    return rowsOf(instanceId).stream().mapToLong(row -> row.seq).max().orElse(-1L) + 1L;
  }

  private List<Row> rowsOf(ProcessInstanceId instanceId) {
    return rows.values().stream().filter(row -> row.instanceId.equals(instanceId)).toList();
  }

  @Override
  public Optional<String> findTransitionIdByInput(
      ProcessInstanceId instanceId, String inputMessageId) {
    return rowsOf(instanceId).stream()
        .filter(row -> row.inputMessageId.equals(inputMessageId))
        .map(row -> row.transitionId)
        .findFirst();
  }

  @Override
  public Optional<String> findLatestTransitionId(ProcessInstanceId instanceId) {
    return rowsOf(instanceId).stream()
        .max(Comparator.comparingLong(row -> row.seq))
        .map(row -> row.transitionId);
  }

  @Override
  public void append(ProcessTransitionInsert t, Instant now) {
    if (findTransitionIdByInput(t.instanceId(), t.inputMessageId()).isPresent()) {
      // UNIQUE(instance_id, input_message_id). The runtime treats this as a retriable conflict and
      // folds into the transition that won, which is only a real test if the double refuses too.
      throw new ConcurrentTransitionException(
          "transition for input "
              + t.inputMessageId()
              + " on instance "
              + t.instanceId().value()
              + " already recorded",
          new IllegalStateException("duplicate key"));
    }
    rows.put(
        t.transitionId(),
        new Row(
            t.tenantId(),
            t.transitionId(),
            t.instanceId(),
            nextSeq(t.instanceId()),
            t.inputMessageId(),
            new PayloadType(t.inputType(), t.inputVersion()),
            t.inputPayload(),
            t.fromLifecycle(),
            t.toLifecycle(),
            t.fromStep(),
            t.toStep(),
            t.decisionCode(),
            t.transitionKind(),
            t.correlationId(),
            Optional.empty(),
            Optional.empty(),
            now));
  }

  @Override
  public void appendOperator(
      String tenantId,
      String transitionId,
      ProcessInstanceId instanceId,
      ProcessLifecycle fromLifecycle,
      ProcessLifecycle toLifecycle,
      ProcessStep fromStep,
      ProcessStep toStep,
      String kind,
      String operator,
      String reason,
      Instant now) {
    rows.put(
        transitionId,
        new Row(
            tenantId,
            transitionId,
            instanceId,
            nextSeq(instanceId),
            // A synthetic input identity, so an operator action never collides with a business
            // input on the dedup key.
            transitionId,
            new PayloadType("aipersimmon.operator", 1),
            new byte[0],
            Optional.of(fromLifecycle),
            toLifecycle,
            Optional.of(fromStep),
            toStep,
            new DecisionCode(kind.toLowerCase(java.util.Locale.ROOT)),
            kind,
            null,
            Optional.ofNullable(operator),
            Optional.ofNullable(reason),
            now));
  }

  @Override
  public List<ProcessTransitionView> timeline(ProcessInstanceId instanceId) {
    return rowsOf(instanceId).stream()
        .sorted(Comparator.comparingLong(row -> row.seq))
        .map(
            row ->
                new ProcessTransitionView(
                    row.transitionId,
                    row.inputMessageId,
                    row.fromLifecycle.map(ProcessLifecycle::name),
                    row.toLifecycle.name(),
                    row.fromStep.map(ProcessStep::value),
                    row.toStep.value(),
                    row.decisionCode.value(),
                    row.transitionKind,
                    row.operator,
                    row.reason,
                    row.createdAt))
        .toList();
  }

  @Override
  public List<ParkedInput> findUnreplayedParkedInputs(ProcessInstanceId instanceId) {
    return rowsOf(instanceId).stream()
        .filter(InMemoryProcessTransitionStore::isUnreplayedPark)
        .sorted(Comparator.comparingLong(row -> row.seq))
        .map(
            row ->
                new ParkedInput(
                    row.tenantId,
                    row.inputMessageId,
                    row.inputType,
                    row.inputPayload,
                    row.correlationId))
        .toList();
  }

  @Override
  public int markParkedReplayed(ProcessInstanceId instanceId, String inputMessageId, Instant now) {
    for (Row row : rowsOf(instanceId)) {
      if (row.inputMessageId.equals(inputMessageId) && isUnreplayedPark(row)) {
        row.replayedAt = now;
        return 1;
      }
    }
    // Already marked, or never parked: what the guarded UPDATE returns when somebody else won.
    return 0;
  }

  @Override
  public List<ProcessInstanceId> findInstancesOwedParkedReplay(int limit) {
    Map<String, Long> oldestDebt = new LinkedHashMap<>();
    for (Row row : rows.values()) {
      if (!isUnreplayedPark(row)) {
        continue;
      }
      oldestDebt.merge(row.instanceId.value(), row.seq, Math::min);
    }
    return oldestDebt.entrySet().stream()
        .filter(entry -> isDrainable(new ProcessInstanceId(entry.getKey())))
        .sorted(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey))
        .limit(limit)
        .map(entry -> new ProcessInstanceId(entry.getKey()))
        .toList();
  }

  /**
   * The join's {@code lifecycle IN (RUNNING, COMPENSATING)}: a suspended instance would only
   * re-park the input and an ended one can no longer be advanced, so neither is offered.
   */
  private boolean isDrainable(ProcessInstanceId instanceId) {
    return instances
        .find(instanceId)
        .filter(
            row ->
                row.lifecycle() == ProcessLifecycle.RUNNING
                    || row.lifecycle() == ProcessLifecycle.COMPENSATING)
        .isPresent();
  }

  private static boolean isUnreplayedPark(Row row) {
    return "PARKED".equals(row.transitionKind) && row.replayedAt == null;
  }

  private record Saved(Set<String> ids, Map<String, Instant> replayedAt) {}

  @Override
  public Object snapshot() {
    Map<String, Instant> replayed = new LinkedHashMap<>();
    rows.forEach((id, row) -> replayed.put(id, row.replayedAt));
    return new Saved(new HashSet<>(rows.keySet()), replayed);
  }

  @Override
  public void restore(Object snapshot) {
    Saved saved = (Saved) snapshot;
    rows.keySet().removeIf(id -> !saved.ids().contains(id) && !committedElsewhere.contains(id));
    saved.replayedAt().forEach((id, at) -> rows.get(id).replayedAt = at);
  }

  // --- what a test needs ------------------------------------------------------------------------

  public Row row(String transitionId) {
    Row row = rows.get(transitionId);
    if (row == null) {
      throw new IllegalArgumentException(
          "no transition " + transitionId + "; holds " + rows.keySet());
    }
    return row;
  }

  /** The transitions of an instance in {@code transition_seq} order. */
  public List<Row> rowsInOrder(ProcessInstanceId instanceId) {
    return rowsOf(instanceId).stream().sorted(Comparator.comparingLong(row -> row.seq)).toList();
  }

  /**
   * Append a row on behalf of another transaction that has already committed, so this transaction's
   * rollback leaves it standing.
   *
   * <p>Without it the losing side of a race cannot be posed at all: an in-memory rollback would
   * take the winner's row back out along with the loser's, and the retry would then find nothing to
   * fold into and simply succeed — turning the conflict path into the happy path while still
   * looking green. What survives a rollback is exactly what another transaction committed, which is
   * what this records.
   */
  public void committedElsewhere(ProcessTransitionInsert transition, Instant now) {
    append(transition, now);
    committedElsewhere.add(transition.transitionId());
  }

  /** A transition insert shaped like the ones the runtime writes, for a test to hand-place. */
  public ProcessTransitionInsert entry(
      String transitionId,
      ProcessInstanceId instanceId,
      String inputMessageId,
      ProcessLifecycle toLifecycle,
      ProcessStep toStep,
      String kind) {
    return new ProcessTransitionInsert(
        "acme",
        transitionId,
        instanceId,
        inputMessageId,
        "sample.payload",
        1,
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        Optional.of(toLifecycle),
        toLifecycle,
        Optional.of(toStep),
        toStep,
        new DecisionCode("carried-on"),
        kind,
        "corr-1");
  }

  /**
   * A parked-input row, as the runtime writes one when an input arrives at a suspended instance.
   * Carries the tenant and correlation the replay has to be performed under, which is the whole
   * reason the park row exists rather than a counter.
   */
  public ProcessTransitionInsert parked(
      String transitionId,
      ProcessInstanceId instanceId,
      String inputMessageId,
      String tenantId,
      String correlationId) {
    return parked(
        transitionId, instanceId, inputMessageId, tenantId, correlationId, "sample.payload");
  }

  /**
   * The same, for a payload whose logical type a test wants to choose (for example a retired one).
   */
  public ProcessTransitionInsert parked(
      String transitionId,
      ProcessInstanceId instanceId,
      String inputMessageId,
      String tenantId,
      String correlationId,
      String payloadType) {
    ProcessStep step = new ProcessStep("awaiting-payment");
    return new ProcessTransitionInsert(
        tenantId,
        transitionId,
        instanceId,
        inputMessageId,
        payloadType,
        1,
        inputMessageId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        Optional.of(ProcessLifecycle.SUSPENDED),
        ProcessLifecycle.SUSPENDED,
        Optional.of(step),
        step,
        new DecisionCode("parked"),
        "PARKED",
        correlationId);
  }

  /** All transition ids, in insertion order — for asserting that nothing extra was written. */
  public List<String> transitionIds() {
    return List.copyOf(rows.keySet());
  }
}

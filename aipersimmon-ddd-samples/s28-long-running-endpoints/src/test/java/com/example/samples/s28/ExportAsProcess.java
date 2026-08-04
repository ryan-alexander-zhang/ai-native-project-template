package com.example.samples.s28;

import com.aipersimmon.ddd.processmanager.definition.HasStep;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec.ProcessSerializationCatalog;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The export, modelled as a durable process. <strong>Test scope only</strong>, and it exists to be measured against
 * rather than copied.
 *
 * <p>This is the shape a team reaches for when they have a process manager and need a job queue: the flow starts, the
 * worker feeds it progress, a cancellation arrives as an input, and it terminates. It is not a strawman — it is a
 * faithful use of the library's API, it compiles, it runs, and the four tables record everything. {@code
 * NotAJobQueueTest} then measures what it costs, against the real engine rather than against its javadoc.
 *
 * <p>Registered from a {@code @TestConfiguration} so that the running sample never starts one. A sample must not ship
 * the shape it is warning about.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExportAsProcess {

  public static final ProcessType PROCESS_TYPE = new ProcessType("s28.export-as-a-process");

  @Bean
  ExportProcessDefinition exportProcessDefinition() {
    return new ExportProcessDefinition();
  }

  @Bean
  ProcessSerializationCatalog exportProcessSerialization() {
    return ProcessSerializationCatalog.builder()
        .payload("s28.export-as-a-process.started", 1, ExportProcessInput.Started.class)
        .payload("s28.export-as-a-process.progressed", 1, ExportProcessInput.Progressed.class)
        .payload("s28.export-as-a-process.cancelled", 1, ExportProcessInput.CancelRequested.class)
        .state(PROCESS_TYPE, new StateSchemaVersion(1), "s28.export-as-a-process.state", ExportProcessState.class)
        .build();
  }

  /** What the flow remembers, including — and this is the point — how far along the export is. */
  public record ExportProcessState(String exportId, long rowsDone, Step step) implements HasStep {

    public enum Step {
      EXPORTING,
      DONE,
      STOPPED
    }

    @Override
    public ProcessStep processStep() {
      return new ProcessStep(step.name());
    }
  }

  /** Everything the flow can be told. {@code payload} is here so the size cap can be measured. */
  public sealed interface ExportProcessInput extends ProcessInput {

    record Started(String exportId, String payload) implements ExportProcessInput {}

    record Progressed(String exportId, long rowsDone) implements ExportProcessInput {}

    record CancelRequested(String exportId) implements ExportProcessInput {}
  }

  /** A faithful definition: pure, no I/O, one decision per input — exactly as the library requires. */
  public static final class ExportProcessDefinition implements ProcessDefinition<ExportProcessState> {

    @Override
    public ProcessType processType() {
      return PROCESS_TYPE;
    }

    @Override
    public Set<Class<?>> declaredPayloads() {
      return Set.of(
          ExportProcessInput.Started.class,
          ExportProcessInput.Progressed.class,
          ExportProcessInput.CancelRequested.class);
    }

    @Override
    public ProcessDecision<ExportProcessState> start(ProcessInput input, ProcessContext context) {
      ExportProcessInput.Started started = (ExportProcessInput.Started) input;
      return ProcessDecision.running(
          new ExportProcessState(started.exportId(), 0, ExportProcessState.Step.EXPORTING),
          "started");
    }

    @Override
    public ProcessDecision<ExportProcessState> react(
        ExportProcessState currentState, ProcessInput input, ProcessContext context) {
      if (input instanceof ExportProcessInput.Progressed progressed) {
        return ProcessDecision.running(
            new ExportProcessState(
                currentState.exportId(), progressed.rowsDone(), ExportProcessState.Step.EXPORTING),
            "progressed");
      }
      if (input instanceof ExportProcessInput.CancelRequested) {
        return ProcessDecision.completed(
            new ExportProcessState(
                currentState.exportId(), currentState.rowsDone(), ExportProcessState.Step.STOPPED),
            "stopped",
            "CANCELLED");
      }
      return ProcessDecision.ignored(context, currentState, input);
    }
  }
}

package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.exception.ProcessPayloadTooLargeException;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Why the process manager is not a job queue — measured against the real engine.
 *
 * <p>The catalogue's question is not "is a process manager any good"; S9 already showed that it is, for what it is
 * for. The question is why <em>this</em> — a unit of long-running compute with progress, a cancellation and an
 * output artifact — must not be modelled as one, and what to do instead. Four answers, and each of them is a
 * measurement rather than a reading of the javadoc:
 *
 * <ol>
 *   <li>the payload cap means the output cannot travel in the state, so the state is a reference — at which point
 *       the process is holding a pointer and the real work is somewhere it does not manage;
 *   <li>every progress tick is a durable transition row and a revision bump, so a job that reports progress a
 *       thousand times leaves a thousand rows and a thousand revisions behind it;
 *   <li>because progress and cancellation are the same revision-checked lane, they serialise against each other —
 *       the counter is in the way of the decision, permanently;
 *   <li>there is no way to stop an instance except by feeding it an input, because the runtime deliberately offers
 *       no {@code setState} or {@code forceStep}. Which is right for a coordinator and wrong for a job.
 * </ol>
 *
 * <p><strong>The positive alternative</strong> is the rest of this sample, and it is not novel: claim the work with
 * a lease, keep the lifecycle in an aggregate, keep the counter out of it, and store the output by reference. That
 * is the shape the library already uses for its own outbox and process-effect relays — S11 named the boundary and
 * left this side of it open.
 *
 * <p>None of which is an argument against process managers. A process manager coordinates <em>other people's</em>
 * work and remembers what has to be undone; the work in each step happens elsewhere and takes milliseconds. A job
 * <em>is</em> the work. The two need opposite things from their storage.
 */
@Import(ExportAsProcess.class)
class NotAJobQueueTest extends ReconciliationTestBase {

  @Autowired private ProcessRuntime processes;
  @Autowired private ProcessQuery processQuery;

  private static final ProcessBusinessKey KEY = new ProcessBusinessKey("exp-as-process");

  /**
   * One megabyte, and a month of settlement rows is not one megabyte.
   *
   * <p>So the artifact has to be a reference — which is the right answer, and is also the point: once the state is a
   * pointer to a file the process manager did not write and cannot clean up, the durable flow is bookkeeping around
   * work it has no part in. The aggregate in this sample holds the same pointer for a tenth of the machinery.
   */
  @Test
  void theoutputCannotTravelInAProcessAtAll() {
    String twoMegabytes = "x".repeat(2 * 1024 * 1024);
    assertThatThrownBy(
            () ->
                processes.start(
                    ExportAsProcess.PROCESS_TYPE,
                    KEY,
                    new ExportAsProcess.ExportProcessInput.Started("exp-1", twoMegabytes),
                    message("too-big")))
        .isInstanceOf(ProcessPayloadTooLargeException.class)
        .hasMessageContaining("exceeding the configured limit of 1048576 bytes");
  }

  /**
   * Twenty progress reports, twenty durable transition rows, twenty revisions.
   *
   * <p>The comparison is the assertion: the same twenty reports against this sample's own design leave one row,
   * overwritten in place, and do not touch the job at all. Multiply by a real export's tick count and the process
   * manager's transition table becomes the largest thing in the database, holding a history of a counter.
   *
   * <p>Retention would not rescue it either: the library's cleanup is off by default and keeps finished instances for
   * thirty days, which is a policy for flows that finish, not for a firehose.
   */
  @Test
  void everyProgressTickIsADurableTransitionAndARevision() {
    var started =
        processes.start(
            ExportAsProcess.PROCESS_TYPE,
            KEY,
            new ExportAsProcess.ExportProcessInput.Started("exp-1", "small"),
            message("start"));

    for (int rows = 1; rows <= 20; rows++) {
      processes.handle(
          started.processRef(),
          new ExportAsProcess.ExportProcessInput.Progressed("exp-1", rows * 1_000L),
          message("tick-" + rows));
    }

    assertThat(transitionCount()).as("one durable row per tick, plus the start").isEqualTo(21);
    assertThat(processQuery.find(started.processRef()).orElseThrow().revision().value())
        .as("and one revision per tick")
        .isEqualTo(21);

    ExportJobId jobId = submit("exp-1");
    long versionBefore = jobVersion("exp-1");
    for (int rows = 1; rows <= 20; rows++) {
      progress.report(jobId, rows * 1_000L, 20_000L);
    }
    assertThat(progressRows()).as("the alternative: one row").hasSize(1);
    assertThat(jobVersion("exp-1")).as("and the job untouched").isEqualTo(versionBefore);
  }

  /**
   * Progress and cancellation share one revision-checked lane, so the counter is in the decision's way.
   *
   * <p>Measured by the revision arithmetic, which is deterministic: a cancellation arriving after twenty ticks is
   * revision 22, not revision 2. Every one of those twenty was a write the cancellation had to be ordered behind, and
   * under real concurrency each is a chance for the cancellation to be refused and retried — with the library's three
   * retries the ceiling is not far away when ticks are frequent.
   */
  @Test
  void acancellationHasToQueueBehindEveryProgressTick() {
    var started =
        processes.start(
            ExportAsProcess.PROCESS_TYPE,
            KEY,
            new ExportAsProcess.ExportProcessInput.Started("exp-1", "small"),
            message("start"));
    for (int rows = 1; rows <= 20; rows++) {
      processes.handle(
          started.processRef(),
          new ExportAsProcess.ExportProcessInput.Progressed("exp-1", rows * 1_000L),
          message("tick-" + rows));
    }
    var cancelled =
        processes.handle(
            started.processRef(),
            new ExportAsProcess.ExportProcessInput.CancelRequested("exp-1"),
            message("stop"));

    assertThat(cancelled.revision().value())
        .as("the cancellation is the 22nd write to this instance, not the 2nd")
        .isEqualTo(22);
  }

  /**
   * There is no back door, and that is a feature of the coordinator that becomes a problem for a job.
   *
   * <p>Asserted over the library's own API surface rather than over anything this sample wrote: {@code ProcessRuntime}
   * offers only {@code start} and {@code handle}. A job needs to be stoppable by an operator whose stop must not be
   * absorbed as "ignored" by whatever step the flow happens to be sitting on — and the only lever available is an
   * input the definition may legitimately choose to ignore.
   */
  @Test
  void aprocessCanOnlyBeStoppedByFeedingItSomething() {
    assertThat(ProcessRuntime.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsOnly("start", "handle");
    assertThat(ProcessQuery.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsOnly("find", "findRef");
  }

  /** A distinct message id per advance: the runtime folds a repeat of the same one, by design. */
  private static CommandContext message(String id) {
    return CommandContext.root(Tenants.ROOT, id);
  }

  private long transitionCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_process_transition", Long.class);
  }
}

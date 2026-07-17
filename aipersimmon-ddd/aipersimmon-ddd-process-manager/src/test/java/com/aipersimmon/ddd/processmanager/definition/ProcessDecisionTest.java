package com.aipersimmon.ddd.processmanager.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The Decision invariants that need no runtime context. */
class ProcessDecisionTest {

    record State(String value) {
    }

    record DeadlineInput(String value) implements ProcessInput {
    }

    private static final DecisionCode CODE = new DecisionCode("decided");
    private static final ProcessStep STEP = new ProcessStep("AWAITING_STOCK");

    @Test
    void runningDecisionWithoutOutcomeIsValid() {
        var decision = new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, List.of());
        assertEquals(ProcessLifecycle.RUNNING, decision.lifecycle());
    }

    @Test
    void terminalDecisionWithOutcomeIsValid() {
        var decision = new ProcessDecision<>(
                new State("s"), ProcessLifecycle.COMPLETED, STEP,
                Optional.of(new ProcessOutcome("ORDER_CONFIRMED")), CODE, List.of());
        assertEquals(ProcessLifecycle.COMPLETED, decision.lifecycle());
    }

    @Test
    void definitionMayNotReturnSuspended() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.SUSPENDED, STEP, Optional.empty(), CODE, List.of()));
    }

    @Test
    void terminalLifecycleRequiresOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.FAILED, STEP, Optional.empty(), CODE, List.of()));
    }

    @Test
    void nonTerminalLifecycleRejectsOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP,
                Optional.of(new ProcessOutcome("x")), CODE, List.of()));
    }

    @Test
    void schedulingAndCancellingTheSameDeadlineInOneDecisionIsAmbiguous() {
        DeadlineName name = new DeadlineName("REVIEW");
        List<ProcessEffect> effects = List.of(
                new ScheduleDeadline(name, Instant.EPOCH, new DeadlineInput("d")),
                new CancelDeadline(name));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, effects));
    }

    @Test
    void distinctDeadlineNamesAreFine() {
        List<ProcessEffect> effects = List.of(
                new ScheduleDeadline(new DeadlineName("REVIEW"), Instant.EPOCH, new DeadlineInput("d")),
                new CancelDeadline(new DeadlineName("PAYMENT")));
        var decision = new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, effects);
        assertEquals(2, decision.effects().size());
    }

    @Test
    void effectsAreDefensivelyCopiedAndUnmodifiable() {
        List<ProcessEffect> mutable = new ArrayList<>();
        mutable.add(new CancelDeadline(new DeadlineName("REVIEW")));
        var decision = new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, mutable);

        mutable.add(new CancelDeadline(new DeadlineName("PAYMENT")));
        assertEquals(1, decision.effects().size(), "later mutation of the source list must not leak in");
        assertThrows(UnsupportedOperationException.class,
                () -> decision.effects().add(new CancelDeadline(new DeadlineName("X"))));
    }

    @Test
    void requiredFieldsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                null, ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), null, STEP, Optional.empty(), CODE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, null, Optional.empty(), CODE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, null, CODE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, null));
    }
}

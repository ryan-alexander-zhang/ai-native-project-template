package com.aipersimmon.ddd.processmanager.jdbc;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A tiny process used by {@link JdbcProcessRuntimeTest}: enough inputs, effects, and a
 * state to exercise start, advance, terminal, compensation, an illegal transition, a
 * failing decision (rollback), and a scheduled deadline. Not a business sample.
 */
final class TestFulfilment {

    private TestFulfilment() {
    }

    static final ProcessType TYPE = new ProcessType("test.proc");
    static final DefinitionVersion VERSION = new DefinitionVersion("v1");
    static final StateSchemaVersion SCHEMA = new StateSchemaVersion(1);

    record State(String step, int count) {
    }

    // Inputs.
    record Started(String orderId) implements ProcessInput {
    }

    record Advance() implements ProcessInput {
    }

    record Finish() implements ProcessInput {
    }

    record Boom() implements ProcessInput {
    }

    record EnterCompensating() implements ProcessInput {
    }

    record IllegalBack() implements ProcessInput {
    }

    record ArmDeadline() implements ProcessInput {
    }

    record FanOut() implements ProcessInput {
    }

    record ArmPoisonDeadline() implements ProcessInput {
    }

    record DeadlineFired() implements ProcessInput {
    }

    // Command effect payload.
    record DoWork(String reference) implements Command<Void> {
    }

    static final class Definition implements ProcessDefinition<State> {
        @Override
        public ProcessType processType() {
            return TYPE;
        }

        @Override
        public DefinitionVersion definitionVersion() {
            return VERSION;
        }

        @Override
        public boolean activeForNewInstances() {
            return true;
        }

        @Override
        public StateSchemaVersion stateSchemaVersion() {
            return SCHEMA;
        }

        @Override
        public ProcessDecision<State> start(ProcessInput input, ProcessContext context) {
            if (input instanceof Started started) {
                return new ProcessDecision<>(
                        new State("S1", 0), ProcessLifecycle.RUNNING, new ProcessStep("S1"),
                        Optional.empty(), new DecisionCode("started"),
                        List.of(new DispatchCommand(new DoWork(started.orderId()))));
            }
            throw new UnsupportedProcessInputException("unexpected start input: " + input);
        }

        @Override
        public ProcessDecision<State> react(State state, ProcessInput input, ProcessContext context) {
            return switch (input) {
                case Advance ignored -> new ProcessDecision<>(
                        new State("S2", state.count() + 1), ProcessLifecycle.RUNNING, new ProcessStep("S2"),
                        Optional.empty(), new DecisionCode("advanced"),
                        List.of(new DispatchCommand(new DoWork("again"))));
                case Finish ignored -> new ProcessDecision<>(
                        new State("DONE", state.count()), ProcessLifecycle.COMPLETED, new ProcessStep("DONE"),
                        Optional.of(new ProcessOutcome("OK")), new DecisionCode("finished"), List.of());
                case Boom ignored -> throw new IllegalStateException("boom");
                case EnterCompensating ignored -> new ProcessDecision<>(
                        new State("COMP", state.count()), ProcessLifecycle.COMPENSATING, new ProcessStep("COMP"),
                        Optional.empty(), new DecisionCode("compensating"), List.of());
                // From COMPENSATING, returning RUNNING is an illegal transition the runtime must reject.
                case IllegalBack ignored -> new ProcessDecision<>(
                        new State("S1", state.count()), ProcessLifecycle.RUNNING, new ProcessStep("S1"),
                        Optional.empty(), new DecisionCode("illegal"), List.of());
                case ArmDeadline ignored -> new ProcessDecision<>(
                        new State("WAIT", state.count()), ProcessLifecycle.RUNNING, new ProcessStep("WAIT"),
                        Optional.empty(), new DecisionCode("armed"),
                        List.of(new ScheduleDeadline(new DeadlineName("REVIEW"),
                                context.now(), new DeadlineFired())));
                case DeadlineFired ignored -> new ProcessDecision<>(
                        new State("DONE", state.count()), ProcessLifecycle.COMPLETED, new ProcessStep("DONE"),
                        Optional.of(new ProcessOutcome("REVIEW_EXPIRED")), new DecisionCode("deadline-fired"),
                        List.of());
                case ArmPoisonDeadline ignored -> new ProcessDecision<>(
                        new State("WAIT_POISON", state.count()), ProcessLifecycle.RUNNING,
                        new ProcessStep("WAIT_POISON"), Optional.empty(), new DecisionCode("armed-poison"),
                        List.of(new ScheduleDeadline(new DeadlineName("POISON"), context.now(), new Boom())));
                case FanOut ignored -> new ProcessDecision<>(
                        new State("FAN", state.count()), ProcessLifecycle.RUNNING, new ProcessStep("FAN"),
                        Optional.empty(), new DecisionCode("fanned"),
                        List.of(new DispatchCommand(new DoWork("first")),
                                new DispatchCommand(new DoWork("second"))));
                default -> throw new UnsupportedProcessInputException("unexpected input: " + input);
            };
        }
    }

    /** A trivial payload codec parameterised by two conversion functions. */
    static <T> ProcessPayloadCodec<T> payloadCodec(
            String logicalType, Class<T> javaType, Function<T, String> encode, Function<String, T> decode) {
        return new ProcessPayloadCodec<>() {
            @Override
            public PayloadType payloadType() {
                return new PayloadType(logicalType, 1);
            }

            @Override
            public Class<T> javaType() {
                return javaType;
            }

            @Override
            public EncodedPayload encode(T value) {
                return new EncodedPayload(payloadType(), encode.apply(value).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public T decode(EncodedPayload payload) {
                return decode.apply(new String(payload.data(), StandardCharsets.UTF_8));
            }
        };
    }

    static List<ProcessPayloadCodec<?>> payloadCodecs() {
        return List.of(
                payloadCodec("test.started", Started.class, Started::orderId, Started::new),
                payloadCodec("test.advance", Advance.class, a -> "", s -> new Advance()),
                payloadCodec("test.finish", Finish.class, f -> "", s -> new Finish()),
                payloadCodec("test.boom", Boom.class, b -> "", s -> new Boom()),
                payloadCodec("test.enter-compensating", EnterCompensating.class,
                        e -> "", s -> new EnterCompensating()),
                payloadCodec("test.illegal-back", IllegalBack.class, i -> "", s -> new IllegalBack()),
                payloadCodec("test.arm-deadline", ArmDeadline.class, a -> "", s -> new ArmDeadline()),
                payloadCodec("test.deadline-fired", DeadlineFired.class, d -> "", s -> new DeadlineFired()),
                payloadCodec("test.fan-out", FanOut.class, f -> "", s -> new FanOut()),
                payloadCodec("test.arm-poison", ArmPoisonDeadline.class, a -> "", s -> new ArmPoisonDeadline()),
                payloadCodec("test.do-work", DoWork.class, DoWork::reference, DoWork::new));
    }

    static ProcessStateCodec<State> stateCodec() {
        return new ProcessStateCodec<>() {
            @Override
            public ProcessType processType() {
                return TYPE;
            }

            @Override
            public StateSchemaVersion schemaVersion() {
                return SCHEMA;
            }

            @Override
            public EncodedPayload encode(State state) {
                String text = state.step() + "|" + state.count();
                return new EncodedPayload(new PayloadType("test.proc.state", SCHEMA.value()),
                        text.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public State decode(EncodedPayload payload) {
                String[] parts = new String(payload.data(), StandardCharsets.UTF_8).split("\\|", 2);
                return new State(parts[0], Integer.parseInt(parts[1]));
            }
        };
    }
}

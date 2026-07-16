package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

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
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** A minimal process used by the starter Boot slice test. */
final class StarterTestProcess {

    private StarterTestProcess() {
    }

    static final ProcessType TYPE = new ProcessType("starter.test");

    record St(String step) {
    }

    record Begin(String reference) implements ProcessInput {
    }

    record DoThing(String reference) implements Command<Void> {
    }

    static final class Definition implements ProcessDefinition<St> {
        @Override
        public ProcessType processType() {
            return TYPE;
        }

        @Override
        public DefinitionVersion definitionVersion() {
            return new DefinitionVersion("v1");
        }

        @Override
        public boolean activeForNewInstances() {
            return true;
        }

        @Override
        public StateSchemaVersion stateSchemaVersion() {
            return new StateSchemaVersion(1);
        }

        @Override
        public ProcessDecision<St> start(ProcessInput input, ProcessContext context) {
            Begin begin = (Begin) input;
            return new ProcessDecision<>(
                    new St("GO"), ProcessLifecycle.RUNNING, new ProcessStep("GO"),
                    Optional.empty(), new DecisionCode("begun"),
                    List.of(new DispatchCommand(new DoThing(begin.reference()))));
        }

        @Override
        public ProcessDecision<St> react(St state, ProcessInput input, ProcessContext context) {
            throw new UnsupportedOperationException("no react in the starter smoke test");
        }
    }

    static ProcessPayloadCodec<Begin> beginCodec() {
        return codec("starter.begin", Begin.class, Begin::reference, Begin::new);
    }

    static ProcessPayloadCodec<DoThing> doThingCodec() {
        return codec("starter.do-thing", DoThing.class, DoThing::reference, DoThing::new);
    }

    static ProcessStateCodec<St> stateCodec() {
        return new ProcessStateCodec<>() {
            @Override
            public ProcessType processType() {
                return TYPE;
            }

            @Override
            public StateSchemaVersion schemaVersion() {
                return new StateSchemaVersion(1);
            }

            @Override
            public EncodedPayload encode(St state) {
                return new EncodedPayload(new PayloadType("starter.test.state", 1),
                        state.step().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public St decode(EncodedPayload payload) {
                return new St(new String(payload.data(), StandardCharsets.UTF_8));
            }
        };
    }

    private static <T> ProcessPayloadCodec<T> codec(
            String logicalType, Class<T> javaType,
            java.util.function.Function<T, String> encode, java.util.function.Function<String, T> decode) {
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
}

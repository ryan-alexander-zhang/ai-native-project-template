package com.aipersimmon.ddd.processmanager.codec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Codec registries index uniquely and fail fast on conflict (design-00004 §3.7). */
class ProcessCodecRegistryTest {

    /** A trivial UTF-8 String codec parameterised by its logical type. */
    static final class StringCodec implements ProcessPayloadCodec<String> {
        private final PayloadType type;

        StringCodec(String logicalType, int version) {
            this.type = new PayloadType(logicalType, version);
        }

        @Override
        public PayloadType payloadType() {
            return type;
        }

        @Override
        public Class<String> javaType() {
            return String.class;
        }

        @Override
        public EncodedPayload encode(String value) {
            return new EncodedPayload(type, value.getBytes(UTF_8));
        }

        @Override
        public String decode(EncodedPayload payload) {
            return new String(payload.data(), UTF_8);
        }
    }

    /** A codec whose Java type is Integer but whose logical type can collide with another. */
    static final class IntegerCodec implements ProcessPayloadCodec<Integer> {
        private final PayloadType type;

        IntegerCodec(String logicalType, int version) {
            this.type = new PayloadType(logicalType, version);
        }

        @Override
        public PayloadType payloadType() {
            return type;
        }

        @Override
        public Class<Integer> javaType() {
            return Integer.class;
        }

        @Override
        public EncodedPayload encode(Integer value) {
            return new EncodedPayload(type, value.toString().getBytes(UTF_8));
        }

        @Override
        public Integer decode(EncodedPayload payload) {
            return Integer.valueOf(new String(payload.data(), UTF_8));
        }
    }

    @Test
    void payloadRegistryLooksUpBothWays() {
        StringCodec codec = new StringCodec("ordering.reserve-stock", 1);
        var registry = new ProcessPayloadCodecRegistry(List.of(codec));

        assertSame(codec, registry.forType(new PayloadType("ordering.reserve-stock", 1)));
        assertSame(codec, registry.forJavaType(String.class));
    }

    @Test
    void payloadRegistryRejectsDuplicateLogicalType() {
        // Same (logicalType, version) but different Java types collides on the payload-type index.
        var a = new StringCodec("ordering.thing", 1);
        var b = new IntegerCodec("ordering.thing", 1);
        assertThrows(IllegalStateException.class,
                () -> new ProcessPayloadCodecRegistry(List.of(a, b)));
    }

    @Test
    void payloadRegistryRejectsDuplicateJavaType() {
        // Two String codecs (different logical types) collide on the Java-type index.
        var a = new StringCodec("ordering.a", 1);
        var b = new StringCodec("ordering.b", 1);
        assertThrows(IllegalStateException.class,
                () -> new ProcessPayloadCodecRegistry(List.of(a, b)));
    }

    @Test
    void payloadRegistryMissThrowsSerializationException() {
        var registry = new ProcessPayloadCodecRegistry(List.of(new StringCodec("ordering.a", 1)));
        assertThrows(ProcessSerializationException.class,
                () -> registry.forType(new PayloadType("ordering.missing", 1)));
        assertThrows(ProcessSerializationException.class,
                () -> registry.forJavaType(Integer.class));
    }

    /** A trivial state codec for a given process type and schema version. */
    static final class StateCodec implements ProcessStateCodec<String> {
        private final ProcessType type;
        private final StateSchemaVersion schema;

        StateCodec(String type, int schema) {
            this.type = new ProcessType(type);
            this.schema = new StateSchemaVersion(schema);
        }

        @Override
        public ProcessType processType() {
            return type;
        }

        @Override
        public StateSchemaVersion schemaVersion() {
            return schema;
        }

        @Override
        public EncodedPayload encode(String state) {
            return new EncodedPayload(new PayloadType(type.value() + ".state", schema.value()), state.getBytes(UTF_8));
        }

        @Override
        public String decode(EncodedPayload payload) {
            return new String(payload.data(), UTF_8);
        }
    }

    @Test
    void stateRegistryLooksUpByTypeAndSchema() {
        StateCodec v1 = new StateCodec("ordering.fulfilment", 1);
        StateCodec v2 = new StateCodec("ordering.fulfilment", 2);
        var registry = new ProcessStateCodecRegistry(List.of(v1, v2));

        assertSame(v1, registry.forState(new ProcessType("ordering.fulfilment"), new StateSchemaVersion(1)));
        assertSame(v2, registry.forState(new ProcessType("ordering.fulfilment"), new StateSchemaVersion(2)));
    }

    @Test
    void stateRegistryRejectsDuplicateTypeSchema() {
        var a = new StateCodec("ordering.fulfilment", 1);
        var b = new StateCodec("ordering.fulfilment", 1);
        assertThrows(IllegalStateException.class,
                () -> new ProcessStateCodecRegistry(List.of(a, b)));
    }

    @Test
    void stateRegistryMissThrowsSerializationException() {
        var registry = new ProcessStateCodecRegistry(List.of(new StateCodec("ordering.fulfilment", 1)));
        var ex = assertThrows(ProcessSerializationException.class,
                () -> registry.forState(new ProcessType("ordering.fulfilment"), new StateSchemaVersion(9)));
        assertEquals(true, ex.getMessage().contains("schema v9"));
    }
}

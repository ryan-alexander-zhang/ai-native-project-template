package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * A Jackson-backed {@link ProcessStateCodec} the convenience layer generates from a
 * {@link ProcessSerializationCatalog} state entry (design-00004 §5.2). It reads and writes exactly
 * the one schema version registered; a schema upgrade is a new explicit codec with an upcaster,
 * never a decode-then-reopen. Serialization failures surface as {@link ProcessSerializationException}.
 *
 * @param <S> the business state type
 */
final class JacksonStateCodec<S> implements ProcessStateCodec<S> {

    private final ProcessType processType;
    private final StateSchemaVersion schemaVersion;
    private final PayloadType type;
    private final Class<S> javaType;
    private final ObjectMapper mapper;

    JacksonStateCodec(ProcessType processType, StateSchemaVersion schemaVersion,
            PayloadType type, Class<S> javaType, ObjectMapper mapper) {
        this.processType = processType;
        this.schemaVersion = schemaVersion;
        this.type = type;
        this.javaType = javaType;
        this.mapper = mapper;
    }

    @Override
    public ProcessType processType() {
        return processType;
    }

    @Override
    public StateSchemaVersion schemaVersion() {
        return schemaVersion;
    }

    @Override
    public EncodedPayload encode(S state) {
        try {
            return new EncodedPayload(type, mapper.writeValueAsBytes(state));
        } catch (IOException e) {
            throw new ProcessSerializationException("cannot encode state of type " + type.logicalType(), e);
        }
    }

    @Override
    public S decode(EncodedPayload payload) {
        try {
            return mapper.readValue(payload.data(), javaType);
        } catch (IOException e) {
            throw new ProcessSerializationException("cannot decode state of type " + type.logicalType(), e);
        }
    }
}

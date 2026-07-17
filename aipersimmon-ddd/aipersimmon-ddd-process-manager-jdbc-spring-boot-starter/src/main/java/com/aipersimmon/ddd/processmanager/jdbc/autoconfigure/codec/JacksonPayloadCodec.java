package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * A Jackson-backed {@link ProcessPayloadCodec} the convenience layer generates from a
 * {@link ProcessSerializationCatalog} entry. The logical type/version and Java
 * type are the catalog's explicit registration, never the class name. Serialization failures are
 * surfaced as {@link ProcessSerializationException}.
 *
 * @param <T> the Java payload type
 */
final class JacksonPayloadCodec<T> implements ProcessPayloadCodec<T> {

    private final PayloadType type;
    private final Class<T> javaType;
    private final ObjectMapper mapper;

    JacksonPayloadCodec(PayloadType type, Class<T> javaType, ObjectMapper mapper) {
        this.type = type;
        this.javaType = javaType;
        this.mapper = mapper;
    }

    @Override
    public PayloadType payloadType() {
        return type;
    }

    @Override
    public Class<T> javaType() {
        return javaType;
    }

    @Override
    public EncodedPayload encode(T value) {
        try {
            return new EncodedPayload(type, mapper.writeValueAsBytes(value));
        } catch (IOException e) {
            throw new ProcessSerializationException("cannot encode payload of type " + type.logicalType(), e);
        }
    }

    @Override
    public T decode(EncodedPayload payload) {
        try {
            return mapper.readValue(payload.data(), javaType);
        } catch (IOException e) {
            throw new ProcessSerializationException("cannot decode payload of type " + type.logicalType(), e);
        }
    }
}

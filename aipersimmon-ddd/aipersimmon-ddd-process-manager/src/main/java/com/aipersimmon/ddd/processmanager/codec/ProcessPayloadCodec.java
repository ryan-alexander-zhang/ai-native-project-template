package com.aipersimmon.ddd.processmanager.codec;

/**
 * Encodes and decodes one payload type — a command, a deadline input, or an
 * integration-event body — between its Java form and an {@link EncodedPayload}.
 * Codecs are registered explicitly by the consumer; the runtime never reflects over
 * business classes. An implementation may use JSON, Avro, Protobuf, or add
 * encryption; the contract does not depend on any of them.
 *
 * @param <T> the Java payload type this codec handles
 */
public interface ProcessPayloadCodec<T> {

    /** The stable logical type/version this codec reads and writes. */
    PayloadType payloadType();

    /** The Java type this codec handles, for encoding lookups. */
    Class<T> javaType();

    /** Encode a value to its persisted form. */
    EncodedPayload encode(T value);

    /** Decode a persisted payload back to its Java form. */
    T decode(EncodedPayload payload);
}

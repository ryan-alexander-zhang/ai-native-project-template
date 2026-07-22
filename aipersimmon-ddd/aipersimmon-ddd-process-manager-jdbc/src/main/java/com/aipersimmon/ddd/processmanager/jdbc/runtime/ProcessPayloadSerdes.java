package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessStateCodecRegistry;
import com.aipersimmon.ddd.processmanager.exception.ProcessPayloadTooLargeException;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;

/**
 * Encodes and decodes process payloads and state, enforcing the {@code payload.max-bytes} cap on
 * encode. Extracted from {@link JdbcProcessRuntime}: the codec concern is cohesive (it touches only
 * the two codec registries and the size cap) and independent of the runtime's orchestration, so it
 * lives as its own collaborator.
 */
final class ProcessPayloadSerdes {

  private final ProcessPayloadCodecRegistry payloadCodecs;
  private final ProcessStateCodecRegistry stateCodecs;
  private final long maxPayloadBytes;

  ProcessPayloadSerdes(
      ProcessPayloadCodecRegistry payloadCodecs,
      ProcessStateCodecRegistry stateCodecs,
      long maxPayloadBytes) {
    this.payloadCodecs = payloadCodecs;
    this.stateCodecs = stateCodecs;
    this.maxPayloadBytes = maxPayloadBytes;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  EncodedPayload encodePayload(Object value) {
    ProcessPayloadCodec codec = payloadCodecs.forJavaType(value.getClass());
    return enforceSize(codec.encode(value));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  EncodedPayload encodeState(ProcessType type, StateSchemaVersion schema, Object state) {
    ProcessStateCodec codec = stateCodecs.forState(type, schema);
    return enforceSize(codec.encode(state));
  }

  Object decodeState(
      ProcessType type, StateSchemaVersion schema, String payloadType, byte[] payload) {
    ProcessStateCodec<?> codec = stateCodecs.forState(type, schema);
    return codec.decode(new EncodedPayload(new PayloadType(payloadType, schema.value()), payload));
  }

  /** Guard the configured {@code payload.max-bytes} cap at encode time. */
  private EncodedPayload enforceSize(EncodedPayload encoded) {
    int size = encoded.data().length;
    if (size > maxPayloadBytes) {
      throw new ProcessPayloadTooLargeException(
          encoded.type().logicalType(), size, maxPayloadBytes);
    }
    return encoded;
  }
}

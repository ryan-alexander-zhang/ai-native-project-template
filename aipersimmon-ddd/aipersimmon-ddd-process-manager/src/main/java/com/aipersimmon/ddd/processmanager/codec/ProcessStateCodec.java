package com.aipersimmon.ddd.processmanager.codec;

import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;

/**
 * Encodes and decodes the business state of one {@link ProcessType} at one
 * {@link StateSchemaVersion}. A running instance keeps decoding under the schema it
 * was written with; a schema upgrade is an explicit new codec (with an upcaster),
 * never a decode-failure-then-reopen.
 *
 * @param <S> the business state type
 */
public interface ProcessStateCodec<S> {

    /** The process type whose state this codec handles. */
    ProcessType processType();

    /** The schema version this codec reads and writes. */
    StateSchemaVersion schemaVersion();

    /** Encode state to its persisted form. */
    EncodedPayload encode(S state);

    /** Decode persisted state back to its Java form. */
    S decode(EncodedPayload payload);
}

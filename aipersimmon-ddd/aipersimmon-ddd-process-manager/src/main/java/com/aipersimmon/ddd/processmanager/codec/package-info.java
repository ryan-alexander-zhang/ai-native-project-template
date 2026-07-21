/**
 * The codec SPI that turns commands, deadline inputs, integration-event bodies, and process state
 * into persisted {@link com.aipersimmon.ddd.processmanager.codec.EncodedPayload}s under stable
 * {@link com.aipersimmon.ddd.processmanager.codec.PayloadType}s — never a Java class name.
 *
 * <p>Consumers register {@link com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec}s and
 * {@link com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec}s; the registries index them
 * with unique keys and fail fast on a conflict. An implementation may use JSON, Avro, or Protobuf
 * and may add encryption — the contract depends on none of them.
 */
package com.aipersimmon.ddd.processmanager.codec;

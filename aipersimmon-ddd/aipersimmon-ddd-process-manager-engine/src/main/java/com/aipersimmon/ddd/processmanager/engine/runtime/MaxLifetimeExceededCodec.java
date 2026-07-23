package com.aipersimmon.ddd.processmanager.engine.runtime;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.definition.MaxLifetimeExceeded;

/**
 * The built-in codec for the runtime's {@link MaxLifetimeExceeded} backstop input. It carries no
 * business fields, so the encoding is empty. Registered by the starter (and available to consumers)
 * whenever the max-lifetime backstop is used, so the runtime can encode the backstop deadline's
 * input at {@code start} and decode it when the deadline fires.
 */
public final class MaxLifetimeExceededCodec implements ProcessPayloadCodec<MaxLifetimeExceeded> {

  private static final PayloadType TYPE = new PayloadType("aipersimmon.max-lifetime-exceeded", 1);
  private static final byte[] EMPTY = new byte[0];

  @Override
  public PayloadType payloadType() {
    return TYPE;
  }

  @Override
  public Class<MaxLifetimeExceeded> javaType() {
    return MaxLifetimeExceeded.class;
  }

  @Override
  public EncodedPayload encode(MaxLifetimeExceeded value) {
    return new EncodedPayload(TYPE, EMPTY);
  }

  @Override
  public MaxLifetimeExceeded decode(EncodedPayload payload) {
    return new MaxLifetimeExceeded();
  }
}

package com.aipersimmon.ddd.processmanager.codec;

import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes {@link ProcessPayloadCodec}s two ways — by {@link PayloadType} (for decoding a persisted
 * row) and by Java type (for encoding a value) — with both indexes unique. Construction fails fast
 * if two codecs claim the same logical type/version or the same Java type. A lookup miss is a
 * {@link ProcessSerializationException}, never a class-name fallback.
 */
public final class ProcessPayloadCodecRegistry {

  private final Map<PayloadType, ProcessPayloadCodec<?>> byPayloadType = new HashMap<>();
  private final Map<Class<?>, ProcessPayloadCodec<?>> byJavaType = new HashMap<>();

  public ProcessPayloadCodecRegistry(Iterable<? extends ProcessPayloadCodec<?>> codecs) {
    for (ProcessPayloadCodec<?> codec : codecs) {
      PayloadType type = codec.payloadType();
      Class<?> javaType = codec.javaType();
      ProcessPayloadCodec<?> byType = byPayloadType.put(type, codec);
      if (byType != null) {
        throw new IllegalStateException(
            "two payload codecs for "
                + type.logicalType()
                + "/v"
                + type.version()
                + ": "
                + byType.getClass().getName()
                + " and "
                + codec.getClass().getName());
      }
      ProcessPayloadCodec<?> byJava = byJavaType.put(javaType, codec);
      if (byJava != null) {
        throw new IllegalStateException(
            "two payload codecs for Java type "
                + javaType.getName()
                + ": "
                + byJava.getClass().getName()
                + " and "
                + codec.getClass().getName());
      }
    }
  }

  /** All registered codecs, for startup consistency checks. */
  public java.util.Collection<ProcessPayloadCodec<?>> codecs() {
    return java.util.Collections.unmodifiableCollection(byJavaType.values());
  }

  /** The codec for a persisted logical type/version. */
  public ProcessPayloadCodec<?> forType(PayloadType type) {
    ProcessPayloadCodec<?> codec = byPayloadType.get(type);
    if (codec == null) {
      throw new ProcessSerializationException(
          "no payload codec registered for " + type.logicalType() + "/v" + type.version());
    }
    return codec;
  }

  /** The codec for a Java value being encoded. */
  @SuppressWarnings("unchecked")
  public <T> ProcessPayloadCodec<T> forJavaType(Class<T> javaType) {
    ProcessPayloadCodec<?> codec = byJavaType.get(javaType);
    if (codec == null) {
      throw new ProcessSerializationException(
          "no payload codec registered for Java type " + javaType.getName());
    }
    return (ProcessPayloadCodec<T>) codec;
  }
}

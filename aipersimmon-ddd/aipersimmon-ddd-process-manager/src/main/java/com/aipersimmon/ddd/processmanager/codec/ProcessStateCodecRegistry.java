package com.aipersimmon.ddd.processmanager.codec;

import com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes {@link ProcessStateCodec}s by {@code (processType, schemaVersion)}, unique. Construction
 * fails fast if two codecs claim the same pair. A lookup miss is a {@link
 * ProcessSerializationException} — a running instance whose schema version has no codec fails
 * loudly rather than silently reopening.
 */
public final class ProcessStateCodecRegistry {

  private record Key(ProcessType processType, StateSchemaVersion schemaVersion) {}

  private final Map<Key, ProcessStateCodec<?>> byKey = new HashMap<>();

  public ProcessStateCodecRegistry(Iterable<? extends ProcessStateCodec<?>> codecs) {
    for (ProcessStateCodec<?> codec : codecs) {
      Key key = new Key(codec.processType(), codec.schemaVersion());
      ProcessStateCodec<?> existing = byKey.put(key, codec);
      if (existing != null) {
        throw new IllegalStateException(
            "two state codecs for "
                + key.processType().value()
                + " schema v"
                + key.schemaVersion().value()
                + ": "
                + existing.getClass().getName()
                + " and "
                + codec.getClass().getName());
      }
    }
  }

  /** The codec for a process type at a schema version. */
  public ProcessStateCodec<?> forState(ProcessType processType, StateSchemaVersion schemaVersion) {
    ProcessStateCodec<?> codec = byKey.get(new Key(processType, schemaVersion));
    if (codec == null) {
      throw new ProcessSerializationException(
          "no state codec registered for "
              + processType.value()
              + " schema v"
              + schemaVersion.value());
    }
    return codec;
  }
}

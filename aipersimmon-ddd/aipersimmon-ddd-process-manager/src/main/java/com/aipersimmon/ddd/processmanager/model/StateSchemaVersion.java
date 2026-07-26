package com.aipersimmon.ddd.processmanager.model;

/**
 * The schema revision of a process's persisted business state, owned by the {@link
 * com.aipersimmon.ddd.processmanager.codec.ProcessStateCodec}. It lets a running instance keep
 * decoding under the schema it was written with; upgrades go through an explicit upcaster rather
 * than reopening the instance on a decode failure.
 *
 * @param value the schema version; {@code >= 1}
 */
public record StateSchemaVersion(int value) {

  /**
   * The schema a state has before it ever migrates, and the default of {@link
   * com.aipersimmon.ddd.processmanager.definition.ProcessDefinition#stateSchemaVersion()}.
   */
  public static final StateSchemaVersion INITIAL = new StateSchemaVersion(1);

  public StateSchemaVersion {
    if (value < 1) {
      throw new IllegalArgumentException("state schema version must be >= 1");
    }
  }
}

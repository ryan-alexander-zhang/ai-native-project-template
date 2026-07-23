package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * A consumer-declared registration list the Jackson convenience layer turns into codec beans. Each
 * entry gives the explicit logical type/version and Java type — a payload (command / deadline input
 * / integration-event body) or a process state (also its process type and schema version). There is
 * no classpath scan and no class-name fallback: an application that needs encryption, upcasting, or
 * a non-JSON format declares its own {@code ProcessPayloadCodec} / {@code ProcessStateCodec} beans
 * instead of using this catalog.
 */
public final class ProcessSerializationCatalog {

  private final List<PayloadEntry> payloads;
  private final List<StateEntry> states;

  private ProcessSerializationCatalog(List<PayloadEntry> payloads, List<StateEntry> states) {
    this.payloads = List.copyOf(payloads);
    this.states = List.copyOf(states);
  }

  public List<PayloadEntry> payloads() {
    return payloads;
  }

  public List<StateEntry> states() {
    return states;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A payload registration: its logical type/version and the Java type carrying it. */
  public record PayloadEntry(PayloadType type, Class<?> javaType) {}

  /** A state registration: its process type, schema version, logical type, and Java type. */
  public record StateEntry(
      ProcessType processType,
      StateSchemaVersion schemaVersion,
      PayloadType type,
      Class<?> javaType) {}

  /** Fluent builder; every entry must be complete. */
  public static final class Builder {
    private final List<PayloadEntry> payloads = new ArrayList<>();
    private final List<StateEntry> states = new ArrayList<>();

    public Builder payload(String logicalType, int version, Class<?> javaType) {
      payloads.add(new PayloadEntry(new PayloadType(logicalType, version), javaType));
      return this;
    }

    public Builder state(
        ProcessType processType,
        StateSchemaVersion schemaVersion,
        String logicalType,
        Class<?> javaType) {
      states.add(
          new StateEntry(
              processType,
              schemaVersion,
              new PayloadType(logicalType, schemaVersion.value()),
              javaType));
      return this;
    }

    public ProcessSerializationCatalog build() {
      return new ProcessSerializationCatalog(payloads, states);
    }
  }
}

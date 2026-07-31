package com.aipersimmon.ddd.processmanager.engine.autoconfigure.codec;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A consumer-declared registration list the Jackson convenience layer turns into codec beans. Each
 * entry gives the explicit logical type/version and Java type — a payload (command / deadline input
 * / integration-event body) or a process state (also its process type and schema version). There is
 * no classpath scan and no class-name fallback: an application that needs encryption, upcasting, or
 * a non-JSON format declares its own {@code ProcessPayloadCodec} / {@code ProcessStateCodec} beans
 * instead of using this catalog.
 *
 * <p>A payload carrying a <em>polymorphic</em> value — a sealed domain interface whose variants
 * Jackson must tell apart — does not need a hand-written codec either: {@link Builder#mixIn}
 * registers a Jackson mix-in class carrying the {@code @JsonTypeInfo}/{@code @JsonSubTypes}
 * declaration, so the domain type itself stays free of serialization annotations. The mix-ins are
 * applied to a codec-private copy of the application's {@code ObjectMapper}; the mapper other
 * components share is never mutated.
 */
public final class ProcessSerializationCatalog {

  private final List<PayloadEntry> payloads;
  private final List<StateEntry> states;
  private final Map<Class<?>, Class<?>> mixIns;

  private ProcessSerializationCatalog(
      List<PayloadEntry> payloads, List<StateEntry> states, Map<Class<?>, Class<?>> mixIns) {
    this.payloads = List.copyOf(payloads);
    this.states = List.copyOf(states);
    this.mixIns = Map.copyOf(mixIns);
  }

  public List<PayloadEntry> payloads() {
    return payloads;
  }

  public List<StateEntry> states() {
    return states;
  }

  /** The Jackson mix-in registrations, keyed by the target type. */
  public Map<Class<?>, Class<?>> mixIns() {
    return mixIns;
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
    private final Map<Class<?>, Class<?>> mixIns = new LinkedHashMap<>();

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

    /**
     * Attach a Jackson mix-in to {@code target} for this catalog's codecs only — the standard route
     * for a payload carrying a sealed/polymorphic domain type: the {@code @JsonTypeInfo} and
     * {@code @JsonSubTypes} declarations live on {@code mixInSource} here in infrastructure, and
     * the domain type stays annotation-free. One mix-in per target; a second registration for the
     * same target is a contradiction and fails at build time.
     */
    public Builder mixIn(Class<?> target, Class<?> mixInSource) {
      Class<?> existing = mixIns.putIfAbsent(target, mixInSource);
      if (existing != null) {
        throw new IllegalArgumentException(
            "two mix-ins registered for "
                + target.getName()
                + ": "
                + existing.getName()
                + " and "
                + mixInSource.getName());
      }
      return this;
    }

    public ProcessSerializationCatalog build() {
      return new ProcessSerializationCatalog(payloads, states, mixIns);
    }
  }
}

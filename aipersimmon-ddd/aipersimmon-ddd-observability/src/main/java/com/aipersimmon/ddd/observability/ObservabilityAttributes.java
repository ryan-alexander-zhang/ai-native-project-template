package com.aipersimmon.ddd.observability;

/**
 * The stable span-attribute catalog. Keeping the keys in one framework-free place lets every span
 * carry the same business dimensions so traces are queryable by them, and keeps them aligned with
 * OpenTelemetry semantic conventions where an equivalent convention exists (the {@code messaging.*}
 * keys mirror the OTEL messaging spec).
 *
 * <p>Business payloads never become attributes — only identity and routing metadata, consistent
 * with "metadata lives beside the payload, not inside it". Sensitive fields must be masked by the
 * caller before being set.
 */
public final class ObservabilityAttributes {

  // Message identity (causation graph).
  public static final String MESSAGE_ID = "message.id";
  public static final String CORRELATION_ID = "correlation.id";
  public static final String CAUSATION_ID = "causation.id";

  // Command / query dispatch.
  public static final String COMMAND_TYPE = "command.type";
  public static final String QUERY_TYPE = "query.type";

  // Domain / integration events.
  public static final String EVENT_TYPE = "event.type";
  public static final String PROJECTION = "projection";

  // Process manager.
  public static final String PROCESS_TYPE = "process.type";
  public static final String PROCESS_BUSINESS_KEY = "process.business_key";
  public static final String PROCESS_INSTANCE_ID = "process.instance_id";
  public static final String PROCESS_DEFINITION_VERSION = "process.definition_version";
  public static final String DECISION_CODE = "process.decision_code";
  public static final String LIFECYCLE = "process.lifecycle";
  public static final String STEP = "process.step";

  // Effects / deadlines / retries.
  public static final String EFFECT_KIND = "effect.kind";
  public static final String EFFECT_INDEX = "effect.index";
  public static final String DEADLINE_NAME = "deadline.name";
  public static final String RETRY_ATTEMPT = "retry.attempt";
  public static final String RETRY_MAX = "retry.max";

  // Messaging transport (aligned with OTEL messaging semantic conventions).
  public static final String MESSAGING_SYSTEM = "messaging.system";
  public static final String MESSAGING_DESTINATION = "messaging.destination";
  public static final String MESSAGING_OPERATION = "messaging.operation";

  private ObservabilityAttributes() {}
}

package com.aipersimmon.ddd.messaging.kafka;

/**
 * Kafka header names for the <a href="https://github.com/cloudevents/spec">CloudEvents</a> v1.0
 * Kafka binding in <em>binary content mode</em>: each CloudEvents attribute travels in a {@code
 * ce_}-prefixed header, the payload is the record value, and {@code content-type} carries the data
 * content type. Shared by the producer dispatcher and the consumer bridge.
 */
public final class IntegrationEventHeaders {

  /** CloudEvents {@code id} — the event's unique id (inbox key). */
  public static final String ID = "ce_id";

  /** CloudEvents {@code source} — the producing context. */
  public static final String SOURCE = "ce_source";

  /** CloudEvents {@code specversion}. */
  public static final String SPEC_VERSION = "ce_specversion";

  /** CloudEvents {@code type} — the logical event type. */
  public static final String TYPE = "ce_type";

  /** CloudEvents {@code time} — ISO-8601 instant the event occurred. */
  public static final String TIME = "ce_time";

  /** CloudEvents {@code subject} — the aggregate id. */
  public static final String SUBJECT = "ce_subject";

  /** Extension: the payload schema version. */
  public static final String DATA_SCHEMA_VERSION = "ce_dataschemaversion";

  /** Extension: correlation id (stable across the flow). */
  public static final String CORRELATION_ID = "ce_correlationid";

  /** Extension: causation id (the message that caused this one). */
  public static final String CAUSATION_ID = "ce_causationid";

  /** CloudEvents Partitioning extension {@code partitionkey} — the Kafka message key. */
  public static final String PARTITION_KEY = "ce_partitionkey";

  /** Standard header carrying the CloudEvents {@code datacontenttype}. */
  public static final String CONTENT_TYPE = "content-type";

  /** The {@code specversion} value this binding emits. */
  public static final String SPEC_VERSION_VALUE = "1.0";

  /** The {@code datacontenttype} value this binding emits (payload is JSON). */
  public static final String CONTENT_TYPE_JSON = "application/json";

  private IntegrationEventHeaders() {}
}

package com.aipersimmon.ddd.processmanager.engine.store;

import java.util.Base64;

/**
 * Encodes codec bytes as base64 text so any codec (JSON, Avro, Protobuf) persists into the {@code
 * text}/{@code CLOB} payload columns of the four-table model without a binary column type.
 */
public final class Payloads {

  private Payloads() {}

  public static String toText(byte[] data) {
    return Base64.getEncoder().encodeToString(data);
  }

  public static byte[] fromText(String text) {
    return Base64.getDecoder().decode(text);
  }
}

package com.aipersimmon.ddd.processmanager.codec;

/**
 * A payload after encoding: its {@link PayloadType} plus the encoded bytes. Bytes (not a String)
 * keep the contract codec-agnostic — JSON, Avro, or Protobuf all fit; the JDBC store decides how to
 * persist them (for example base64 in a text column). The array is defensively copied so the record
 * stays effectively immutable.
 *
 * @param type the logical type/version of the payload
 * @param data the encoded bytes
 */
public record EncodedPayload(PayloadType type, byte[] data) {

  public EncodedPayload {
    if (type == null) {
      throw new IllegalArgumentException("payload type required");
    }
    if (data == null) {
      throw new IllegalArgumentException("payload data required");
    }
    data = data.clone();
  }

  @Override
  public byte[] data() {
    return data.clone();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof EncodedPayload other
        && type.equals(other.type)
        && java.util.Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return 31 * type.hashCode() + java.util.Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return "EncodedPayload[type=" + type + ", data=" + data.length + " bytes]";
  }
}

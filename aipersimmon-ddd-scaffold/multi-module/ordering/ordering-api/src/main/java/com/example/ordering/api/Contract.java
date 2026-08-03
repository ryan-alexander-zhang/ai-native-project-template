package com.example.ordering.api;

/**
 * The published language's shared refusals: parse, don't validate. A payload that cannot honour the
 * contract is refused in the record's compact constructor, so the consuming bridge sees a
 * construction failure at deserialization time — Jackson surfaces it as a {@code
 * ValueInstantiationException}, a {@code JsonProcessingException}, which the consumer's error
 * handler already dead-letters at once — instead of nulls travelling into a handler and failing
 * there as an ambiguous, pointlessly retried NPE. Package-private: this is how the contract types
 * are built, not part of the contract.
 */
final class Contract {

  private Contract() {}

  /** Refuses a null or blank value, naming the field the payload failed to supply. */
  static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " required");
    }
    return value;
  }
}

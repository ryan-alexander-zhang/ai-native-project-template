package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Stored as one JSON column, not as four.
 *
 * <p>The judgement is about how it is used: nothing queries, sorts or joins on a line of an address,
 * and the shape changes more often than the schema wants to. Where a value object <em>is</em> queried —
 * {@link Money} — flattening wins. Getting this backwards is how a table ends up with a JSON column
 * everyone greps into, or with fourteen address columns nobody reads.
 */
@ValueObject
public record ShippingAddress(String recipient, String street, String city, String postcode) {

  public ShippingAddress {
    if (recipient == null || recipient.isBlank()) {
      throw new IllegalArgumentException("recipient must not be blank");
    }
  }
}

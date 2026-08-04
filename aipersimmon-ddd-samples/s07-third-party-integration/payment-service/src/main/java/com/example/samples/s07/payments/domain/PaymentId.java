package com.example.samples.s07.payments.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A payment's identity — and, not by coincidence, the idempotency key this service sends to the
 * gateway.
 *
 * <p>Reusing it is the decision that makes the outbound call safe to repeat. The alternative, a key
 * minted per attempt, would make every retry a fresh charge. The requirement it imposes is that the id
 * exists <em>before</em> anything is sent, which is why it is minted by the command handler from the
 * framework's {@code IdGenerator} rather than by the database on insert: a key you learn after the call
 * cannot protect the call.
 */
@ValueObject
public record PaymentId(String value) implements Identifier {

  public PaymentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("payment id must not be blank");
    }
  }
}

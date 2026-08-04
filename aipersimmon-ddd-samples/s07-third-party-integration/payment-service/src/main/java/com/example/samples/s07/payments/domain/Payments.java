package com.example.samples.s07.payments.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/**
 * The write port. Two methods, and no candidate scan: "which payments look stuck" is the reconciler's
 * question, not the aggregate's, so it lives on its own port in the application layer (S11 draws the
 * same line for its sweep).
 */
@Repository
public interface Payments {

  void save(Payment payment);

  Optional<Payment> find(PaymentId id);
}

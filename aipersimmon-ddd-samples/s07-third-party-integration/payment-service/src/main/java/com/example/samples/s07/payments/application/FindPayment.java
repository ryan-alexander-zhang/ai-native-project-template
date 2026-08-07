package com.example.samples.s07.payments.application;

import com.aipersimmon.ddd.cqrs.Query;
import java.util.Optional;

/** Ask for one payment. Empty when this service has never been asked to make it. */
public record FindPayment(String paymentId) implements Query<Optional<PaymentView>> {}

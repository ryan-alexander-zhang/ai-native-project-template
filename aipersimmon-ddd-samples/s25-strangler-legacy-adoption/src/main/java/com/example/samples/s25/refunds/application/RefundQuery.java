package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.cqrs.Query;
import jakarta.validation.constraints.Min;

/** Read one refund back through the new context. */
public record RefundQuery(@Min(1) long refundId) implements Query<RefundView> {}

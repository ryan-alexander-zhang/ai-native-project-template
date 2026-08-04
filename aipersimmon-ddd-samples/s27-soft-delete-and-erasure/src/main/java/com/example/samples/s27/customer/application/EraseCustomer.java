package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import jakarta.validation.constraints.NotBlank;

/**
 * Erase the personal data, keep the record that it happened.
 *
 * <p>The audit template names the customer's <strong>id</strong> and nothing else, which is the whole design of
 * this row: it has to survive the erasure — it is the evidence the obligation was discharged — so it must not
 * contain anything the erasure was supposed to remove. Every other command here can afford to be careless
 * about that; this one cannot, and it is the one people write last.
 *
 * <p>{@code ticket} is the request that authorised it. Recording that is the difference between an audit trail
 * that proves compliance and one that proves an employee deleted a customer's data.
 */
@OperationLog(
    code = "customer.erase",
    targetType = "Customer",
    targetId = "${input.customerId}",
    success = "Erased the personal data of ${input.customerId} under request ${input.ticket}",
    failure = "Could not erase ${input.customerId}: ${failure.code}")
public record EraseCustomer(@NotBlank String customerId, @NotBlank String ticket)
    implements Command<Void> {}

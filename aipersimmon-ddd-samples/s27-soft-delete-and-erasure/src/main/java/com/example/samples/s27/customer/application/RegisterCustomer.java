package com.example.samples.s27.customer.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Register a customer. The id is supplied, so a failed registration is still auditable — see S14 §3. */
public record RegisterCustomer(
    @NotBlank String customerId,
    @NotBlank @Email String email,
    @NotBlank String displayName,
    String phone)
    implements Command<Void> {}

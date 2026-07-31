package com.aipersimmon.ddd.archunit.fixture.validation.bad;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;

/**
 * One component declares its contract, the other says nothing — is {@code reason} required, or
 * optional? Nobody can tell without opening the handler, which is exactly what the rule refuses.
 */
public record BadUnconstrainedCommand(@NotBlank String orderId, String reason)
    implements Command<Void> {}

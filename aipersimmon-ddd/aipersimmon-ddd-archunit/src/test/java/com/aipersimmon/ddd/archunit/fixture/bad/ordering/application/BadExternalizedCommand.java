package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.integration.Externalized;

/**
 * Violates {@code externalizedShouldOnlyAnnotateIntegrationEvents}: {@code @Externalized} on a type
 * that is not an integration event, so nothing ever performs the transport lookup the annotation
 * exists for. It is inert while reading as though this type is published to a broker.
 */
@Externalized("ordering.events")
public record BadExternalizedCommand(String orderId) implements Command<Void> {}

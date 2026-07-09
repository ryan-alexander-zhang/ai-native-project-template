package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Port for publishing an integration event to other bounded contexts. The
 * application calls this with a plain integration event; the infrastructure layer
 * decides the transport and stamps the transport metadata (event id, timestamp),
 * so the application stays free of clock, id generation, and framework concerns.
 */
public interface IntegrationEvents {

    void publish(IntegrationEvent event);
}

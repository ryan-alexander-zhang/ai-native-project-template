package com.example.samples.s07.payments.infrastructure.gateway;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.outbox.EventDestinations;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Where an event goes, answered for a deployment whose only external target is an HTTP API.
 *
 * <p>The library requires this bean and refuses to default it. {@code OutboxWriter}'s javadoc gives the
 * reason: a writer that quietly fell back to "everything is in-process" would stamp every row as local,
 * and every externalized event would then be delivered locally and marked sent — silent loss, which is
 * exactly what storing the destination on the row exists to prevent. With no transport installed the
 * honest answer is {@code ALL_IN_PROCESS}, passed explicitly; here the honest answer is this class.
 *
 * <p>It is a twenty-line reimplementation of something the library already ships —
 * {@code ExternalizedRoutes} in {@code aipersimmon-ddd-messaging-kafka} does the same job, reading the
 * same annotation, resolving the same placeholders. It is reimplemented rather than reused because that
 * class arrives bundled with a Kafka transport, a consumer bridge and a routing dispatcher, and this
 * service has no broker. That is the honest cost of a transport-shaped module: a deployment that
 * externalizes to something else re-does the small part.
 *
 * <p>Unlike the library's version, this one resolves on each publish instead of precomputing a map at
 * startup. One annotation read and one placeholder resolution per published event is nothing at payment
 * volumes; at broker volumes it would not be, which is why the library precomputes.
 */
@Component
class GatewayDestinations implements EventDestinations {

  private final IntegrationEventCatalog catalog;
  private final Environment environment;

  GatewayDestinations(IntegrationEventCatalog catalog, Environment environment) {
    this.catalog = catalog;
    this.environment = environment;
  }

  @Override
  public Optional<String> destinationFor(String type, int version) {
    return catalog
        .lookup(type, version)
        .flatMap(IntegrationEvent::externalizedTarget)
        .map(environment::resolveRequiredPlaceholders);
  }
}

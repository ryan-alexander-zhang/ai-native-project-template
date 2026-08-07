/**
 * Inbound adapters: the integration-event subscriber that keeps the projection current, and the HTTP
 * edge that serves it.
 *
 * <p>An integration event arrives over a transport at the boundary, so its subscriber belongs here
 * rather than in the application layer — which is what the library's ArchUnit rule enforces. The
 * controller is here for the same reason and not a different one: both are transports, and neither
 * decides anything.
 */
package com.example.samples.s12.ordering.adapter;

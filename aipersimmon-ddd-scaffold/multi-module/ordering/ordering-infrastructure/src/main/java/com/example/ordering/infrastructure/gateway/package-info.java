/**
 * Outbound (driven) adapters that reach other bounded contexts — ordering's
 * anti-corruption layer. The stock-availability gateway implements the application's
 * {@code StockAvailabilityGateway} port over inventory's published
 * {@code StockAvailabilityApi}, translating between the two contexts' languages and
 * hiding whether the call is in-process or remote.
 */
package com.example.ordering.infrastructure.gateway;

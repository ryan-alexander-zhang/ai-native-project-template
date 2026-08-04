/**
 * The use cases, and the layer where the provider's vocabulary has already been forgotten.
 *
 * <p>Read the imports: no HTTP client, no result codes, no signature, no topic. Three commands and two
 * ports, of which {@link com.example.samples.s07.payments.application.GatewayCharges} is the only one
 * that admits a foreign system exists at all — and it does so in our words, returning a sealed
 * {@link com.example.samples.s07.payments.application.GatewayReport} whose four cases are the four
 * situations a payment integration actually has to survive.
 */
package com.example.samples.s07.payments.application;

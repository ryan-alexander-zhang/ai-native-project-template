/**
 * Infrastructure layer of the payment context: technical implementations of the application ports.
 * The context owns no persisted aggregate, so its only adapter is the in-memory operation-dedupe
 * log that backs the {@code PaymentOperations} port.
 */
@InfrastructureLayer
package com.example.payment.infrastructure;

import com.aipersimmon.ddd.core.architecture.InfrastructureLayer;

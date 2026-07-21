package com.aipersimmon.ddd.archunit.fixture.bad.ordering.api;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * A cross-context integration event, subscribed to in the wrong layer (see the application
 * fixture).
 */
public class BadStockReserved implements IntegrationEvent {}

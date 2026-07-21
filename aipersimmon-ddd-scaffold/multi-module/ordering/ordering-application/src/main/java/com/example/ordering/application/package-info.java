/**
 * Application layer of the ordering context: use-case services that orchestrate the domain and the
 * ports they depend on. It depends inward on the domain only, never on the infrastructure or
 * interface layers.
 */
@ApplicationLayer
package com.example.ordering.application;

import com.aipersimmon.ddd.core.architecture.ApplicationLayer;

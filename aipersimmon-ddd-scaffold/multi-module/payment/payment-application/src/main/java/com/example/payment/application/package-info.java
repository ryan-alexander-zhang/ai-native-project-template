/**
 * Application layer of the payment context: the charge-payment use case that applies the
 * domain authorization rule and announces the outcome as an integration event. Depends inward
 * on the domain and on this context's own published contract to emit it.
 */
@ApplicationLayer
package com.example.payment.application;

import com.aipersimmon.ddd.core.architecture.ApplicationLayer;

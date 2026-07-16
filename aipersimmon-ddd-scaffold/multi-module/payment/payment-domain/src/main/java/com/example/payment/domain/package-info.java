/**
 * Domain layer of the payment context: the authorization rule, free of framework and
 * infrastructure concerns. The context is stateless — it decides an outcome rather than
 * holding a balance — so the rule lives in a pure {@link com.example.payment.domain.AuthorizationPolicy}
 * returning a {@link com.example.payment.domain.PaymentDecision}.
 */
@DomainLayer
package com.example.payment.domain;

import com.aipersimmon.ddd.core.architecture.DomainLayer;

/**
 * The payment context's published contract for other bounded contexts: thin integration events
 * reporting the outcome of a payment authorization — {@link
 * com.example.payment.api.PaymentAuthorized} and {@link com.example.payment.api.PaymentDeclined}.
 * They carry the order id and, on decline, a stable machine-readable code, never any internal
 * payment model.
 */
package com.example.payment.api;

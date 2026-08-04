/**
 * The use cases, and the layer that owns the global-transaction boundary.
 *
 * <p>This package imports Seata, and that is a deliberate decision rather than a leak: "these writes are
 * one outcome" is a statement about a business transaction, so the annotation that says it belongs where
 * the business transaction is defined. Neither the domain below nor the controller above says it.
 */
package com.example.samples.s10.banking.application;

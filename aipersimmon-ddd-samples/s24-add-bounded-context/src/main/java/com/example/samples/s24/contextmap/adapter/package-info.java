/**
 * Inbound adapters for facts published by other contexts.
 *
 * <p>An integration event arrives over a transport, so its subscriber is an adapter — the library's own rule, opted into
 * here. Today the transport is the in-process event publisher; the class does not know that and would not change if it
 * became a broker.
 */
package com.example.samples.s24.contextmap.adapter;

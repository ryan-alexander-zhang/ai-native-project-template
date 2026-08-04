/**
 * The flow itself: its inputs, its state, and the definition that decides between them.
 *
 * <p>It lives in {@code application} rather than {@code infrastructure} because that is what it is — a
 * policy over use cases, naming this context's own commands and nothing technical. Swap the coordinator and
 * these three files are what survives; the two classes that talk to the engine are one package over, in
 * {@code infrastructure.fulfilment}.
 */
package com.example.samples.s09.ticketing.application.fulfilment;

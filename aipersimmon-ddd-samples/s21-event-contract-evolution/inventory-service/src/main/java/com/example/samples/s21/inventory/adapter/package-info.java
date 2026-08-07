/**
 * The inbound edge, and the only place in this service that knows the contract has a history.
 *
 * <p>Everything below it — command, handler, aggregate — is written against one revision. That is
 * the division of labour worth copying: revisions are a boundary concern, and a boundary concern
 * that leaks inward turns every future bump into a change to the domain.
 *
 * <p>The HTTP read sits alongside the upcaster on purpose: it is how "where did this reservation
 * land, and under which revision did it arrive" gets answered from outside the process.
 */
package com.example.samples.s21.inventory.adapter;

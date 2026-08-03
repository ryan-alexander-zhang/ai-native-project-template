/**
 * The inbound edge, and the only place in this service that knows the contract has a history.
 *
 * <p>Everything below it — command, handler, aggregate — is written against one revision. That is the
 * division of labour worth copying: revisions are a boundary concern, and a boundary concern that
 * leaks inward turns every future bump into a change to the domain.
 */
package com.example.samples.s21.inventory.adapter;

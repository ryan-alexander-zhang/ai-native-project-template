/**
 * The inbound edge for events. A separate package from {@code ..interfaces..} on purpose: the
 * library's rule for integration-event subscribers names {@code ..adapter..}, and the two edges do
 * differ — one is called by a client, the other by a transport that will call again.
 */
package com.example.samples.s04.inventory.adapter;

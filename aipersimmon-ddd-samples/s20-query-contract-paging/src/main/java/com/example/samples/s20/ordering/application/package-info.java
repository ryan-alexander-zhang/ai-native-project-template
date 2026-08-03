/**
 * The read side's contract, and a small write side to feed it.
 *
 * <p>Four things live here that a list endpoint is usually missing: the request as a value ({@code
 * PageRequest}), the ordering as a closed set ({@code OrderSort}), the filter as a value with a
 * stable digest ({@code OrderFilter}), and the cursor's contents with the only code that opens them
 * ({@code PageCursor}). What is left for the adapter is SQL, and what is left for the controller is
 * binding.
 */
package com.example.samples.s20.ordering.application;

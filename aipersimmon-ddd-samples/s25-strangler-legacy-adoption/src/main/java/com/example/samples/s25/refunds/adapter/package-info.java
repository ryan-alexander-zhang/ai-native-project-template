/**
 * The refunds context's HTTP edge, which goes through the strangler seam rather than round it.
 *
 * <p>A new endpoint that dispatched the command directly would create a second way into the table, and then the route
 * switch would stop describing the system.
 */
package com.example.samples.s25.refunds.adapter;

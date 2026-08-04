/**
 * The ordering context, which was here before coupons arrived.
 *
 * <p>What adding a context did to it: one optional field on a request, one nullable field on a response, one dependency in
 * one handler, and one published event it was going to want anyway. Nothing in {@code domain} changed. That is the
 * measure of whether the boundary was drawn in the right place.
 */
package com.example.samples.s24.ordering;

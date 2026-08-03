/**
 * The published language — one class, at the revision this deploy emits.
 *
 * <p>Retired revisions are not kept here. A publisher that keeps them keeps the ability to emit them,
 * which is the one thing a contract owner must not have during a migration: two revisions of the same
 * fact on the wire means every consumer that knows both applies it twice.
 */
package com.example.samples.s21.ordering.api;

/**
 * The use cases, in this context's language — two of them, chosen to contrast.
 *
 * <p>One carries absolute state and a revision, and needs no dedup key because its effect is idempotent
 * by content. The other carries a delta, and cannot be made safe without one. Which mechanism a message
 * needs is a property of the message, not a house style.
 */
package com.example.samples.s05.catalog.application;

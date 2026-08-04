/**
 * What the ordering context announces. LOCAL events (no {@code @Externalized}), because S23 has no broker —
 * but they are still contracts, and the outbox row holding one is still the payload a consumer would see.
 */
package com.example.samples.s23.ordering.api;

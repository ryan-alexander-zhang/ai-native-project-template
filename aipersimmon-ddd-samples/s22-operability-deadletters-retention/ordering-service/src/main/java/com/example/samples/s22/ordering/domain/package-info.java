/**
 * The order aggregate. One root, one invariant, no knowledge of delivery: nothing in this package
 * mentions the outbox, a topic, an attempt count or a dead letter. That is the property S22 leans on
 * hardest — every operational failure in this sample happens after the domain has finished, which is
 * why an operator can replay a message without asking the domain's permission.
 */
package com.example.samples.s22.ordering.domain;

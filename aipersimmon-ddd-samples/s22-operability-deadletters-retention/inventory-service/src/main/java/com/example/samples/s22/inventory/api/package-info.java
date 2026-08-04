/**
 * This service's own model of the ordering context's contract. A separate Java class from the
 * publisher's, agreeing only on the wire identity {@code (name, version)} — and in an operability sample
 * that agreement is the load-bearing part: every poison record in the other tests is a record whose
 * {@code (ce_type, ce_dataschemaversion)} pair no class here answers.
 */
package com.example.samples.s22.inventory.api;

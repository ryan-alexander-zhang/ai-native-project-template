/**
 * Every revision of the consumed contract that can still arrive — three of them, where the publisher
 * holds one.
 *
 * <p>That asymmetry is the sample's thesis. A published contract's revisions do not retire when the
 * publisher stops emitting them; they retire when the last record at that revision has been read, and
 * only a consumer can know when that is.
 */
package com.example.samples.s21.inventory.api;

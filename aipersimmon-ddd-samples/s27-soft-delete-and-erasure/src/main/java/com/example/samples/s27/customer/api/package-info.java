/**
 * The published contracts, and the reason an erasure is not a local matter.
 *
 * <p>Two of these three events quote personal data, because a consumer that has to keep a copy of a customer
 * needs the values and not just the id. That is the ordinary, correct design — and it means the moment an
 * event is published, <strong>the erasure obligation is distributed</strong>: this service can overwrite its
 * own columns, and every copy downstream is somebody else's row in somebody else's database.
 *
 * <p>Which is what {@link com.example.samples.s27.customer.api.CustomerErased} is for. It carries no personal
 * data — there is none left to carry — and its whole job is to be the instruction every consumer needs in
 * order to discharge the same obligation. An erasure that overwrote the local row and published nothing would
 * be complete in exactly one database.
 */
package com.example.samples.s27.customer.api;

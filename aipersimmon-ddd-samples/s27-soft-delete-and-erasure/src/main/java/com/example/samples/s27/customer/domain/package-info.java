/**
 * The customer aggregate. It knows about two of the three deletions and, deliberately, not the third.
 *
 * <p>It knows {@code CLOSED}, because that is a state a rule reads and a person can be told about. It knows
 * {@code erase()}, because overwriting personal data changes what the aggregate <em>is</em> and every
 * invariant about it has to survive that.
 *
 * <p>It has never heard of {@code deleted}. That column is the infrastructure's way of hiding a row, and a
 * domain that could read it would grow rules that branch on whether a record is visible — which is a
 * statement about the persistence layer masquerading as a business rule.
 */
package com.example.samples.s27.customer.domain;

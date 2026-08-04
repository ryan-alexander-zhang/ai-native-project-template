/**
 * The order aggregate, in its current shape. Nothing here records that the shape ever changed, and that is
 * the property a migration exists to preserve: the domain models what is true now, and the history of how
 * the table got here lives in {@code db/migration} where it can be read in order.
 */
package com.example.samples.s23.ordering.domain;

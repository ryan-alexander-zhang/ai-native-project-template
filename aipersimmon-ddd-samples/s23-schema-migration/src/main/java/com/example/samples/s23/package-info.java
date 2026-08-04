/**
 * S23: two bounded contexts in one database, and a table whose shape changed after it had data in it.
 *
 * <p>The sample is one deployable on purpose. Migration questions get interesting exactly when several
 * owners share a schema, and the smallest honest version of that is two contexts plus the framework's own
 * component tables.
 */
package com.example.samples.s23;

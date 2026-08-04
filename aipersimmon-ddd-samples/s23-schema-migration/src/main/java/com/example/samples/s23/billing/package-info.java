/**
 * The billing context. Deliberately thin — one aggregate, one table — because its job in this sample is to be
 * a <em>second owner of the same database</em>, which is what makes "whose migration is V2" a real question.
 *
 * <p>It shares a datasource with ordering and nothing else: no shared table, no cross-context foreign key, no
 * import in either direction. Sharing a database is a deployment fact; sharing a schema would be a modelling
 * mistake.
 */
package com.example.samples.s23.billing;

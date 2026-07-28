/**
 * Persistence adapter for the Customer aggregate, behind the domain's {@code Customers} port. Read
 * only in this application: no use case writes a customer, so the port exposes no {@code save} and
 * the rows come from the schema migration.
 */
package com.example.ordering.infrastructure.persistence.customer;

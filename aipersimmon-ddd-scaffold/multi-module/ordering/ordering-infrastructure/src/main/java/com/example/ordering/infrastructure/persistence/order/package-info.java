/**
 * Persistence adapters for the Order aggregate: the write-side repository behind the domain's
 * {@code Orders} port, and the read-side implementation of the application's {@code OrderQueries}.
 * The two are deliberately separate — see {@code MyBatisOrderQueries} for why the read side is not
 * a repository.
 */
package com.example.ordering.infrastructure.persistence.order;

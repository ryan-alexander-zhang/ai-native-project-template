package com.aipersimmon.ddd.core.model;

/**
 * The root entity of an aggregate: the only member reachable from outside the aggregate, and the
 * boundary of one transactional consistency unit. Reference an aggregate from another aggregate by
 * its identity (see {@link Association}), never by holding the root instance.
 *
 * @param <ID> the identity type of the root
 */
public interface AggregateRoot<ID> extends Entity<ID> {}

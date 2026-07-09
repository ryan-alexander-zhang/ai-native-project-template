package com.aipersimmon.ddd.core.model;

/**
 * A by-identity reference from one aggregate to another. Holding an
 * {@code Association} instead of the target root instance enforces the rule that
 * aggregates are linked by identity, keeping their object graphs and
 * transactional boundaries separate.
 *
 * @param <T>  the referenced aggregate root type
 * @param <ID> the referenced root's identity type
 */
public interface Association<T extends AggregateRoot<ID>, ID> {

    /** Identity of the referenced aggregate root. */
    ID id();
}

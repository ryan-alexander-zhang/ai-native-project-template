/**
 * The tactical building blocks that carry weight in the type system: {@link
 * com.aipersimmon.ddd.core.model.Identifier}, {@link com.aipersimmon.ddd.core.model.Association},
 * and {@link com.aipersimmon.ddd.core.model.AbstractAggregateRoot}.
 *
 * <p>Every role a building block can play is <em>named</em> once, in {@link
 * com.aipersimmon.ddd.core.annotation}. What lives here is only the part a name cannot do: hold
 * recorded events and an optimistic-lock version, bound a generic, define equality. There used to
 * be {@code AggregateRoot} and {@code Entity} interfaces here as well, echoing two of the six
 * annotations under a second copy of their names — so declaring an aggregate meant choosing between
 * two spellings, using both in one file meant writing one of them fully qualified, and the
 * architecture rules had to chase both. They are gone; extending {@link
 * com.aipersimmon.ddd.core.model.AbstractAggregateRoot} is the contract, and {@code @AggregateRoot}
 * is the name.
 */
package com.aipersimmon.ddd.core.model;

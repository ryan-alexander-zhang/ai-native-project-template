/**
 * The tactical building blocks that carry weight in the type system: {@link
 * com.aipersimmon.ddd.core.model.Identifier} and {@link
 * com.aipersimmon.ddd.core.model.AbstractAggregateRoot}, bound together — an aggregate's identity
 * type must be an {@code Identifier}, so "ids are dedicated value objects" is enforced by the
 * compiler rather than remembered. (An {@code Association} abstraction for by-identity references
 * between aggregates used to live here too; nothing in the library or its scaffold ever needed more
 * than holding the target's {@code Identifier} directly, so it was a name without a job and was
 * removed.)
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

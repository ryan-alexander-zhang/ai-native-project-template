package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodMoney;
import com.aipersimmon.ddd.cqrs.ReadModel;

/**
 * A well-formed read model: a projection in the application layer, holding the fields the answer
 * needs and no aggregate. Exercises the good path of {@code readModelsShouldBeProjectionShapes}.
 *
 * <p>The {@link GoodMoney} component is the deliberate control. A domain <em>value object</em> in a
 * read model is allowed — there is no identity to load and no lifecycle to run — so a rule that had
 * been written as "a read model must not touch the domain package" would report this, and reporting
 * it would push projections towards re-spelling the model's vocabulary in bare primitives.
 */
@ReadModel
public record GoodOrderView(String orderId, GoodMoney total) {}

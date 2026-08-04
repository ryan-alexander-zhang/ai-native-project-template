/**
 * The use cases, the read model, and the projection that maintains it.
 *
 * <p>Four ports live here and every one of them is the read side asking for something in a shape it cannot
 * misuse: {@link OrderFacts} (the write model, flattened, no aggregates), {@link ProductNames} (this
 * context's replica of the catalogue's names), {@link OrderListWriter} (whole rows only) and {@link
 * OrderListQueries}. Infrastructure implements all four with SQL; none of the logic that decides what a row
 * contains lives down there.
 */
package com.example.samples.s12.ordering.application;

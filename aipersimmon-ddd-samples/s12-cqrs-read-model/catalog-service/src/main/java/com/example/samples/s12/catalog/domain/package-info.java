/**
 * The catalogue's model: one aggregate, one interesting attribute.
 *
 * <p>It knows nothing about who displays a product name. That ignorance is the design: the moment this
 * context has to know which other contexts cache its names, the cache stops being theirs and becomes a
 * distributed responsibility with no owner.
 */
package com.example.samples.s12.catalog.domain;

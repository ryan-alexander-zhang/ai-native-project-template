/**
 * The HTTP surface: the product endpoints, and the cache's operations endpoints.
 *
 * <p>The second group is the one worth having an opinion about. A cache without a way to look at it and a way
 * to drop it is a cache that gets restarted instead, which on a shared Redis takes every other service's
 * entries with it.
 */
package com.example.samples.s26.catalog.adapter;

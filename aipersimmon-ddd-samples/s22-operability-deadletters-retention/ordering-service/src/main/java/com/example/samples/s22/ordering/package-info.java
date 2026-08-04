/**
 * The publishing side of S22: an ordering context whose interesting property is not what it
 * publishes but what it does when publishing fails.
 *
 * <p>The domain is deliberately thin — one aggregate, one event — because everything this sample has
 * to say lives in the operations surface, the retention settings, and the guards that run at
 * startup. S4 is where the publish/consume flow itself is explained.
 */
package com.example.samples.s22.ordering;

/**
 * One sku's stock, and the rule that you cannot reserve what is not there. The rule earns its place in
 * an operability sample by being the thing a stalled partition is protecting: a record that cannot be
 * handled must not be allowed to become a reservation that was never checked.
 */
package com.example.samples.s22.inventory.domain;

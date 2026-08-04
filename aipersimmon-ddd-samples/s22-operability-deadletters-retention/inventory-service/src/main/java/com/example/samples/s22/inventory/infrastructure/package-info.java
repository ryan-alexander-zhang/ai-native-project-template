/** Persistence for the stock aggregate. The inbox row is written by the framework's store, in the same
 * transaction as the reservation — which is what makes a failed delivery roll back its own dedup record
 * instead of suppressing its retry.
 */
package com.example.samples.s22.inventory.infrastructure;

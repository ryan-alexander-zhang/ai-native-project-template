/**
 * The consuming side of S22. Its subject is the one question a publisher cannot answer for you: what a
 * partition does with a record it cannot handle, and what has to have been provisioned beforehand for
 * the answer to be "set it aside" rather than "stop".
 */
package com.example.samples.s22.inventory;

/**
 * The account aggregate. It has no idea it is one branch of a distributed transaction, and the sample's
 * central claim is that it should not have to: under AT, "the debit is undone if the points fail" is a
 * property of the infrastructure, not a state in the model.
 */
package com.example.samples.s10.banking.domain;

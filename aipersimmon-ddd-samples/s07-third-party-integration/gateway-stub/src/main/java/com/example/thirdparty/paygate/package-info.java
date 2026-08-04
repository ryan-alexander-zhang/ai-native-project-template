/**
 * A payment gateway that is not ours.
 *
 * <p>The package name is the first line of the sample's argument: {@code com.example.thirdparty}, not
 * {@code com.example.samples}. Nothing in the payment service's own packages may name a type from
 * here, and an ArchUnit rule over there says so — the only place these classes appear is the payment
 * service's test classpath, which is exactly the reach a provider's SDK should have.
 */
package com.example.thirdparty.paygate;

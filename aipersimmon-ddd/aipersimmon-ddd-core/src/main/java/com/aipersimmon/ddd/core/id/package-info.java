/**
 * The framework-free identifier SPI.
 *
 * <p>{@link com.aipersimmon.ddd.core.id.IdGenerator} is the seam every framework component mints
 * its per-row / per-message ids through. The contract asks for a globally-unique, time-ordered
 * string; the default UUIDv7 implementation and its auto-configuration live in the optional {@code
 * aipersimmon-ddd-id-spring-boot-starter} module, so this package stays dependency-free.
 */
package com.aipersimmon.ddd.core.id;

/**
 * The optional UUIDv7 implementation of the {@link com.aipersimmon.ddd.core.id.IdGenerator} SPI.
 *
 * <p>{@link com.aipersimmon.ddd.id.Uuidv7IdGenerator} is a time-ordered, JUG-backed generator;
 * {@code AipersimmonDddIdAutoConfiguration} binds it as the default {@code IdGenerator} whenever
 * this module is on the classpath. Every framework minting point then resolves its id supplier to
 * it. A build that omits this module keeps {@code UUID.randomUUID()} at each minting point, so the
 * pure tier stays free of the JUG dependency (see {@code decision-00019} / {@code design-00010}).
 */
package com.aipersimmon.ddd.id;

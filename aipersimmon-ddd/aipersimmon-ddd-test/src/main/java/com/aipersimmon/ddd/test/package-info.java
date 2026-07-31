/**
 * The framework's ports as officially-maintained test doubles, plus the {@link
 * com.aipersimmon.ddd.test.WithTenant} JUnit 5 extension. Add this artifact at <em>test</em> scope;
 * nothing here belongs on a production classpath.
 *
 * <p>Why the framework ships these instead of leaving each team to hand-roll them: a fake is a
 * claim about the real implementation's semantics, and the subtle ones — {@code send} mints ids
 * while {@code sendAs} must not, an envelope's source falls back from the contract to the
 * deployment, inbox identity is the {@code (source, messageKey)} pair — are exactly the ones a
 * quick anonymous class gets wrong, making causation and dedup assertions pass vacuously
 * (issue-00140).
 */
package com.aipersimmon.ddd.test;

/**
 * The monolith, and <strong>nothing in here uses the library</strong>.
 *
 * <p>No aggregate, no repository base class, no command bus, no outbox, no MyBatis-Plus. That is what makes it
 * legacy: it predates the decision to adopt anything, and the entire scenario is about what can be done without
 * rewriting it. A sample whose legacy side quietly used the library's building blocks would be answering a
 * question nobody has.
 *
 * <p>Two rules stand over this package, both in {@code ArchitectureTest}:
 *
 * <ul>
 *   <li><strong>only the ACL may depend on it.</strong> Not the new context's domain, not its application, not a
 *       controller "just for now". One seam or none;
 *   <li><strong>it may not depend on the new context.</strong> The one exception is the entry point that
 *       delegates, which lives in {@code acl} rather than here for exactly that reason.
 * </ul>
 *
 * <p>The refund methods are still here after they stop being reachable. That is deliberate: keeping them makes
 * the switch revertible by a config value rather than by a rollback, and deleting them is the last step of the
 * migration rather than the first — with a criterion, measured by {@code DoneCriterionTest}.
 */
package com.example.samples.s25.legacy;

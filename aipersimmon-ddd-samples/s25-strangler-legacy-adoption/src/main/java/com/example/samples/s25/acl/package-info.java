/**
 * The anti-corruption layer: two classes, and the answer to "how does legacy code get wrapped" is that number.
 *
 * <p>{@code LegacyOrders} translates the monolith's facts into the new context's words — a status string into a boolean,
 * a JDBC exception into an {@code Optional}, a legacy record into a port's own type. {@code LegacyRefundEntryPoint} is
 * the seam the strangler switches: the legacy signature, routed by configuration.
 *
 * <p>Two ArchUnit rules make this a boundary rather than a naming convention: only this package may depend on
 * {@code legacy}, and {@code legacy} may not depend on the new context. The delegating entry point lives here rather
 * than in the monolith precisely because of the second one.
 *
 * <p>It is also the package that disappears. When the order is extracted too, {@code LegacyOrders} becomes a call into
 * another context; when the legacy entry point's callers are gone, that class goes with them.
 */
package com.example.samples.s25.acl;

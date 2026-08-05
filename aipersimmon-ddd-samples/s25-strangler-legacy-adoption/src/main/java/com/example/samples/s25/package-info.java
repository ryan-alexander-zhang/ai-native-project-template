/**
 * S25: taking the first aggregate out of a legacy monolith, on a schema the library does not own.
 *
 * <p>The library's defaults assume you own the schema: a version column, a minted identity, framework tables. This
 * scenario assumes none of it, and the six answers are all measured:
 *
 * <ol>
 *   <li><strong>Which aggregate first</strong> — the one with the fewest writers and the most rules.
 *       {@code LegacyFanInTest} counts both out of the legacy service, so the choice is a number rather than an opinion;
 *   <li><strong>No version column</strong> — add one, and know what it does not buy. With two writers the optimistic
 *       lock cannot see the one that does not participate, so a legacy {@code UPDATE} is silently overwritten.
 *       {@code VersionColumnTest} measures the lost update, and then measures it gone once the legacy path delegates;
 *   <li><strong>Auto-increment versus a minted id</strong> — keep the {@code bigint} as the internal identity, reserve it
 *       from the table's sequence before the insert (the library will not insert a row without one), and publish a
 *       {@code public_id} UUID outward from day one. {@code AutoIncrementIdentityTest};
 *   <li><strong>Double writes</strong> — do not. One writer, two readers. The outbox can feed the new context, but only
 *       for writes that come through the library's transaction, which the monolith's never will.
 *       {@code DoubleWriteTest};
 *   <li><strong>The ACL</strong> — two classes, and one ArchUnit rule that nothing else may touch the monolith. The
 *       pattern name buys nothing; the rule buys everything;
 *   <li><strong>Done</strong> — when no legacy method writes the table, no caller reaches the legacy entry point, and the
 *       foreign key is gone. {@code DoneCriterionTest} computes it from the code and the schema rather than asking
 *       anybody.
 * </ol>
 *
 * <p>One departure from the catalogue's suggested components, on purpose: it proposed the JDBC variants of the framework
 * modules as "closer to legacy SQL". The legacy side here uses <strong>no framework module at all</strong> — plain
 * {@code JdbcTemplate} and hand-written SQL, which is closer still — and the new context uses MyBatis-Plus like every
 * other sample in this series. The interesting frictions turn out to be in the write path and the schema, not in the SQL
 * dialect.
 */
package com.example.samples.s25;

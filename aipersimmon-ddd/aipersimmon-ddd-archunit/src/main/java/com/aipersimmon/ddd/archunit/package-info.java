/**
 * Reusable structural checks that enforce the DDD layering and building-block
 * conventions across any project that adopts these building blocks:
 * {@link com.aipersimmon.ddd.archunit.AiPersimmonDddRules} provides ArchUnit rules
 * (run against compiled classes), and
 * {@link com.aipersimmon.ddd.archunit.PackageInfoChecks} provides a source-level
 * check that every package declares a {@code package-info.java} — done at source
 * level because a package-info without annotations produces no class file and is
 * invisible to bytecode analysis.
 */
package com.aipersimmon.ddd.archunit;

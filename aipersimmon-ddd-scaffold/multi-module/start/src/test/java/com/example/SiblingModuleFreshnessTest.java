package com.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Every {@code com.example} class this module tests against must come from the reactor, not from
 * the local Maven repository.
 *
 * <p>{@code mvn test -pl start} without {@code -am} looks like a reasonable shortcut in a
 * seventeen-module build whose acceptance tests start containers. It is not one. {@code -pl}
 * shrinks the reactor to the listed modules; the other sixteen are then ordinary dependencies,
 * resolved from {@code ~/.m2} as whatever {@code 0.0.1-SNAPSHOT} was installed there last. Maven
 * prints no warning, because from its point of view the resolution succeeded — the SNAPSHOT
 * coordinate is the same string for the working copy and for a jar built two days ago, so nothing
 * distinguishes them.
 *
 * <p>What makes this worth a test rather than a line in the README is how the failure presents. The
 * review that filed the issue ran exactly that command and got {@code expected:<400> but was:<405>}
 * from a controller test plus a thirty-second timeout in a flow test. Both point at application
 * code. The truth was that a stale {@code ordering-adapter} jar had three of the five endpoints, so
 * {@code GET /orders} matched a path with no such method. A reader who has just finished reading
 * every source file in the repository still reached the wrong conclusion and nearly changed code
 * that was not broken. Documentation only reaches whoever read it; this reaches everyone.
 *
 * <p>The check is on where each class was <em>loaded from</em>, not on the build command. A class
 * read out of a directory is freshly compiled output, whatever produced it — {@code target/classes}
 * under Maven, an IDE's output directory otherwise — so directories always pass. A class read out
 * of a jar is only trustworthy when the jar sits under some module's {@code target/}, which is what
 * a full-reactor {@code verify} produces after {@code package}. A jar anywhere else is a repository
 * artifact, and that is the one case this fails on.
 */
class SiblingModuleFreshnessTest {

  @Test
  void noApplicationClassIsLoadedFromTheLocalRepository() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.example");

    // Keyed by jar so a stale module is reported once rather than once per class it contains.
    Map<String, String> staleJars = new TreeMap<>();
    for (JavaClass imported : classes) {
      imported
          .getSource()
          .map(source -> source.getUri().toString())
          .map(SiblingModuleFreshnessTest::jarOutsideTheReactor)
          .ifPresent(jar -> staleJars.putIfAbsent(jar, imported.getName()));
    }

    assertTrue(
        staleJars.isEmpty(),
        () ->
            "these classes came from the local Maven repository, not from this working copy, so"
                + " the tests below would have run against whatever was installed there last:\n"
                + staleJars.entrySet().stream()
                    .map(entry -> "  " + entry.getKey() + "\n    e.g. " + entry.getValue())
                    .reduce("", (all, line) -> all + line + "\n")
                + "Re-run with -am (mvn -o test -pl start -am), which builds the sibling modules"
                + " from source first. Any failures you saw before adding -am are suspect.");
  }

  /**
   * The jar this class URI names, when that jar is not build output of some module in this reactor;
   * {@code null} when the source is trustworthy. A jar URI reads {@code
   * jar:file:/path/to/x.jar!/com/example/X.class}.
   */
  private static String jarOutsideTheReactor(String classUri) {
    int separator = classUri.indexOf(".jar!");
    if (separator < 0) {
      return null; // a directory of compiled classes — always this working copy
    }
    String jar = classUri.substring(0, separator + ".jar".length());
    return jar.contains("/target/") ? null : jar;
  }
}

package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No main source in this library cites a repository-internal document id ({@code issue-000xx},
 * {@code design-000xx}, …). Those ids resolve only inside this repository's {@code docs/} tree; to
 * a consumer reading the published javadoc — the only audience library comments have — they are
 * dead references that name evidence the reader cannot open.
 *
 * <p>The rule ("library comments are self-contained: inline the conclusion, don't cite the ticket")
 * predates this test, and that is exactly why the test exists: with nothing enforcing it, the
 * 2026-07-30 review counted ~28 citations, and by the time this gate landed the count had grown to
 * 50 — several of them written <em>during</em> the remediation of that review, by an author who
 * knew the rule. A convention that loses to muscle memory needs a mechanism.
 *
 * <p>What to do when this fails: say the reason in place. Almost every violating comment already
 * restates the conclusion next to the id, so deleting the citation usually suffices; where the id
 * carried the whole argument, move the argument into the comment. The scope is everything that
 * ships — all of {@code src/main} (javadoc, SQL migration comments, resources) and every {@code
 * pom.xml}, since a module's pom is published with its comments intact. Test sources are exempt —
 * they are not published — as is the scaffold, which lives in the same repository as the documents
 * it cites.
 */
class LibraryCommentsAreSelfContainedTest {

  private static final Pattern DOCS_ID =
      Pattern.compile("(issue|design|decision|analysis|plan)-\\d{5}");

  @Test
  void nothingThatShipsCitesARepositoryDocsId() throws IOException {
    Path root = reactorRoot();
    List<String> citations = new ArrayList<>();
    collectCitations(root, root.resolve("pom.xml"), citations);
    try (Stream<Path> modules = Files.list(root)) {
      for (Path module : modules.filter(Files::isDirectory).toList()) {
        collectCitations(root, module.resolve("pom.xml"), citations);
        Path mainSources = module.resolve("src/main");
        if (Files.isDirectory(mainSources)) {
          try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
              collectCitations(root, file, citations);
            }
          }
        }
      }
    }

    assertTrue(
        citations.isEmpty(),
        () ->
            "published sources cite repository-internal document ids that no consumer of this "
                + "library can resolve — inline the conclusion instead of citing the ticket:\n"
                + String.join("\n", citations));
  }

  private static void collectCitations(Path root, Path file, List<String> citations)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    // Malformed bytes decode to replacement characters rather than failing the scan: a binary
    // resource cannot contain a docs id, and must not be able to hide one either.
    List<String> lines =
        new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
            .lines()
            .toList();
    for (int i = 0; i < lines.size(); i++) {
      Matcher matcher = DOCS_ID.matcher(lines.get(i));
      while (matcher.find()) {
        citations.add(root.relativize(file) + ":" + (i + 1) + " cites " + matcher.group());
      }
    }
  }

  /** The reactor root, found by walking up from this module rather than assumed. */
  private static Path reactorRoot() {
    for (Path candidate = Path.of("").toAbsolutePath();
        candidate != null;
        candidate = candidate.getParent()) {
      if (Files.exists(candidate.resolve("aipersimmon-ddd-bom/pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("no reactor root above " + Path.of("").toAbsolutePath());
  }
}

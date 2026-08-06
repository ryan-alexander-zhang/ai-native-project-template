package com.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Nothing in this scaffold cites a template-repository document id (the {@code issue-}, {@code
 * design-}, {@code decision-}, {@code analysis-}, {@code plan-}, {@code report-} or {@code prd-}
 * prefix followed by a five-digit number). Those ids resolve only inside the repository the
 * template was authored in; in a project generated from this archetype they are dead references —
 * provenance the reader cannot open. A comment here must carry its own reasoning: inline the
 * conclusion, don't cite the ticket.
 *
 * <p>Unlike the sibling library's equivalent gate, the scope is the <em>whole</em> reactor tree —
 * test sources, markdown, YAML, SQL, poms, everything — because the whole tree ships as the
 * archetype: a generated project contains a copy of every one of these files.
 *
 * <p>This is a plain filesystem test, deliberately not a Spring or ArchUnit test: it must not add
 * an application context (the context count is pinned elsewhere) and it must see resources and
 * documents that never reach a classpath.
 */
class ShippedCommentsAreSelfContainedTest {

  private static final Pattern DOCS_ID =
      Pattern.compile("(issue|design|decision|analysis|plan|report|prd)-\\d{5}");

  /**
   * The walk must have covered a substantial tree; a misresolved root that scanned nothing (or
   * almost nothing) must fail rather than pass vacuously.
   */
  private static final int MINIMUM_FILES_SCANNED = 100;

  @Test
  void nothingThatShipsCitesATemplateRepositoryDocsId() throws IOException {
    Path root = reactorRoot();
    List<String> citations = new ArrayList<>();
    int scanned = scan(root, citations);

    assertTrue(
        scanned > MINIMUM_FILES_SCANNED,
        () -> "scanned only " + scanned + " files under " + root + " — the walk is broken");
    assertTrue(
        citations.isEmpty(),
        () ->
            "shipped files cite template-repository document ids that no generated project can "
                + "resolve — inline the reasoning instead of citing the document:\n"
                + String.join("\n", citations));
  }

  /** Walks every regular file under the root, skipping build output and hidden directories. */
  private static int scan(Path root, List<String> citations) throws IOException {
    int[] scanned = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName().toString();
            if (name.equals("target") || (name.startsWith(".") && !dir.equals(root))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            scanned[0]++;
            collectCitations(root, file, citations);
            return FileVisitResult.CONTINUE;
          }
        });
    return scanned[0];
  }

  private static void collectCitations(Path root, Path file, List<String> citations)
      throws IOException {
    // Malformed bytes decode to replacement characters rather than failing the scan: a binary
    // file cannot contain a docs id, and must not be able to hide one either.
    List<String> lines =
        new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      Matcher matcher = DOCS_ID.matcher(lines.get(i));
      while (matcher.find()) {
        citations.add(root.relativize(file) + ":" + (i + 1) + " cites " + matcher.group());
      }
    }
  }

  /**
   * The reactor root, found by walking up from this module rather than assumed. The marker has to
   * be something a <em>generated</em> project has too — this test runs there as well, and anything
   * that exists only in the authoring repository (an {@code archetype.properties}, say) would make
   * it throw for every consumer. An aggregator pom next to a {@code start} module is the first
   * ancestor that qualifies from either tree, and nothing above it in either tree does.
   */
  private static Path reactorRoot() {
    for (Path candidate = Path.of("").toAbsolutePath();
        candidate != null;
        candidate = candidate.getParent()) {
      if (Files.exists(candidate.resolve("pom.xml"))
          && Files.exists(candidate.resolve("start/pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("no reactor root above " + Path.of("").toAbsolutePath());
  }
}

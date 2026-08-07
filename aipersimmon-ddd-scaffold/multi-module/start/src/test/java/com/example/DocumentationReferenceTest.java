package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that no file in this project names a document the generated project does not have.
 *
 * <p>This tree is the source of the {@code multi-module} archetype and ships on its own, so a
 * reference to one of the library's documents resolves to nothing once the project is generated —
 * the reader is sent looking for a file that is not there. The whole reactor is walked rather than
 * enumerated, so a new module or a new document is covered the moment it is added.
 *
 * <p>A mention of a Markdown document is accepted three ways, in the order the reader would try
 * them:
 *
 * <ol>
 *   <li>it resolves to a file that actually ships — relative to the mentioning file, or to the
 *       reactor root;
 *   <li>the same line carries an absolute URL for it, which is how README's "Upstream
 *       documentation" table holds the four library links; or
 *   <li>the mention is marked <em>upstream</em> on its own line or the one above, which is the rule
 *       that section states for everywhere else in the tree.
 * </ol>
 *
 * <p>Naming the section inside an upstream document is encouraged — that is the token a reader
 * searches for. It is the bare filename, written as if it were a local path, that this test
 * rejects.
 */
class DocumentationReferenceTest {

  /** The surefire working directory is the {@code start} module, so its parent is the reactor. */
  private static final Path REACTOR_ROOT = Path.of("..");

  private static final Set<String> SCANNED_EXTENSIONS =
      Set.of(".md", ".yml", ".yaml", ".java", ".xml", ".properties");

  private static final Pattern MARKDOWN_REFERENCE = Pattern.compile("[A-Za-z0-9._/-]+\\.md");

  private static final Pattern ABSOLUTE_URL = Pattern.compile("https?://\\S+");

  private static final String UPSTREAM_MARKER = "upstream";

  @Test
  void everyDocumentReferenceResolvesInAGeneratedProject() throws IOException {
    List<Path> files = scannedFiles();
    assertFalse(
        files.isEmpty(),
        "no scannable files found under "
            + REACTOR_ROOT.toAbsolutePath()
            + " — the reactor layout changed and this discovery is stale");

    List<String> dangling = new ArrayList<>();
    for (Path file : files) {
      dangling.addAll(danglingReferencesIn(file));
    }
    assertEquals(
        List.of(),
        dangling,
        """
        These references do not resolve in a generated project. Either point at a file that ships \
        with it, carry the absolute URL on the same line, or mark the mention "upstream" — see \
        README's "Upstream documentation" section.""");
  }

  private static List<String> danglingReferencesIn(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> dangling = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String previous = i > 0 ? lines.get(i - 1) : "";
      if (isMarkedUpstream(line) || isMarkedUpstream(previous)) {
        continue;
      }
      List<String> urls = matches(ABSOLUTE_URL, line);
      Matcher references = MARKDOWN_REFERENCE.matcher(stripUrls(line, urls));
      while (references.find()) {
        String reference = references.group();
        if (shipsWithTheProject(file, reference) || isLinkedOnTheSameLine(reference, urls)) {
          continue;
        }
        dangling.add(REACTOR_ROOT.relativize(file) + ":" + (i + 1) + ": " + reference);
      }
    }
    return dangling;
  }

  /**
   * Whether the reference resolves to a file the archetype generates. Three bases are tried,
   * covering how each kind of file expresses a path: the mentioning file's own directory (a
   * Markdown link), its Maven module (a {@code Path.of("../README.md")} in a test, since surefire
   * runs from the module directory), and the reactor root.
   *
   * <p>Every candidate is clamped to the reactor. A {@code ../} that climbs out of it resolves in
   * this repository — where the scaffold sits beside the library — and in a generated project
   * resolves to nothing, which is the whole failure this test exists to catch.
   */
  private static boolean shipsWithTheProject(Path file, String reference) {
    Path reactor = REACTOR_ROOT.toAbsolutePath().normalize();
    for (Path base : List.of(file.getParent(), moduleRootOf(file), REACTOR_ROOT)) {
      Path candidate = base.resolve(reference).toAbsolutePath().normalize();
      if (candidate.startsWith(reactor) && Files.isRegularFile(candidate)) {
        return true;
      }
    }
    return false;
  }

  /** The nearest ancestor holding a {@code pom.xml} — the directory surefire would run from. */
  private static Path moduleRootOf(Path file) {
    Path reactor = REACTOR_ROOT.toAbsolutePath().normalize();
    for (Path directory = file.toAbsolutePath().normalize().getParent();
        directory != null && directory.startsWith(reactor);
        directory = directory.getParent()) {
      if (Files.isRegularFile(directory.resolve("pom.xml"))) {
        return directory;
      }
    }
    return REACTOR_ROOT;
  }

  /** Whether the line carries an absolute URL that ends in the referenced document. */
  private static boolean isLinkedOnTheSameLine(String reference, List<String> urls) {
    String fileName = reference.substring(reference.lastIndexOf('/') + 1);
    return urls.stream().anyMatch(url -> trimTrailingPunctuation(url).endsWith(fileName));
  }

  private static boolean isMarkedUpstream(String line) {
    return line.toLowerCase(Locale.ROOT).contains(UPSTREAM_MARKER);
  }

  private static String stripUrls(String line, List<String> urls) {
    String stripped = line;
    for (String url : urls) {
      stripped = stripped.replace(url, " ");
    }
    return stripped;
  }

  private static List<String> matches(Pattern pattern, String line) {
    return pattern.matcher(line).results().map(result -> result.group()).toList();
  }

  private static String trimTrailingPunctuation(String url) {
    int end = url.length();
    while (end > 0 && ")].,;:".indexOf(url.charAt(end - 1)) >= 0) {
      end--;
    }
    return url.substring(0, end);
  }

  /** Every text file in the reactor this test understands, excluding build output. */
  private static List<Path> scannedFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(REACTOR_ROOT)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(DocumentationReferenceTest::hasScannedExtension)
          .filter(path -> !isUnderTarget(path))
          .filter(DocumentationReferenceTest::isReadableText)
          .sorted()
          .toList();
    }
  }

  private static boolean hasScannedExtension(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && SCANNED_EXTENSIONS.contains(name.substring(dot));
  }

  private static boolean isUnderTarget(Path path) {
    for (Path segment : path) {
      if (segment.toString().equals("target")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isReadableText(Path path) {
    try {
      Files.readAllLines(path, StandardCharsets.UTF_8);
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path + " as UTF-8 text", e);
    }
  }
}

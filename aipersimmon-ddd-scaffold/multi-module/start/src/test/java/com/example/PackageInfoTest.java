package com.example;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every source package across the whole reactor declares a package-info.java.
 *
 * <p>The {@code src/main/java} roots are discovered by walking the reactor root, not enumerated, so
 * a new module (or a new context) is covered the moment it is added — nothing here needs editing.
 * Checked at source level (from the reactor root) because a package-info without annotations
 * produces no class file and is invisible to bytecode analysis.
 */
class PackageInfoTest {

  /** The surefire working directory is the {@code start} module, so its parent is the reactor. */
  private static final Path REACTOR_ROOT = Path.of("..");

  private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");

  @Test
  void everyPackageHasPackageInfo() throws IOException {
    List<Path> sourceRoots = mainSourceRoots();
    assertFalse(
        sourceRoots.isEmpty(),
        "no src/main/java roots found under "
            + REACTOR_ROOT.toAbsolutePath()
            + " — the reactor layout changed and this discovery is stale");
    for (Path sourceRoot : sourceRoots) {
      PackageInfoChecks.assertEveryPackageHasPackageInfo(sourceRoot);
    }
  }

  /**
   * Every {@code src/main/java} directory in the reactor, excluding build output ({@code target}).
   */
  private static List<Path> mainSourceRoots() throws IOException {
    try (Stream<Path> paths = Files.walk(REACTOR_ROOT)) {
      return paths
          .filter(Files::isDirectory)
          .filter(path -> path.endsWith(MAIN_SOURCE_ROOT))
          .filter(path -> !isUnderTarget(path))
          .sorted()
          .toList();
    }
  }

  private static boolean isUnderTarget(Path path) {
    for (Path segment : path) {
      if (segment.toString().equals("target")) {
        return true;
      }
    }
    return false;
  }
}

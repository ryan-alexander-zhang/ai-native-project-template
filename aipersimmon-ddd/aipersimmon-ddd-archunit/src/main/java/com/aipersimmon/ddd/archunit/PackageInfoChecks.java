package com.aipersimmon.ddd.archunit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Source-level check that every package declares a {@code package-info.java}.
 *
 * <p>This is done over source directories rather than with ArchUnit on purpose: a
 * {@code package-info.java} that carries only Javadoc (no annotations) is not
 * compiled to a {@code package-info.class}, so bytecode analysis cannot tell a
 * missing file from an annotation-less one and would report false positives.
 * Walking the source tree is exact.
 *
 * <p>Use it from a test, pointing at the module's source root:
 *
 * <pre>{@code
 * @Test
 * void every_package_has_package_info() {
 *     PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
 * }
 * }</pre>
 */
public final class PackageInfoChecks {

    private PackageInfoChecks() {
    }

    /**
     * Directories under {@code sourceRoot} that contain at least one Java source
     * file but no {@code package-info.java}.
     *
     * @param sourceRoot a source root such as {@code src/main/java}
     */
    public static List<Path> packagesMissingPackageInfo(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("not a directory: " + sourceRoot);
        }
        try (Stream<Path> dirs = Files.walk(sourceRoot)) {
            return dirs.filter(Files::isDirectory)
                    .filter(dir -> containsJavaSource(dir)
                            && !Files.exists(dir.resolve("package-info.java")))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Assert that every source package under {@code sourceRoot} declares a
     * {@code package-info.java}.
     *
     * @throws AssertionError listing the offending packages, if any
     */
    public static void assertEveryPackageHasPackageInfo(Path sourceRoot) {
        List<Path> missing = packagesMissingPackageInfo(sourceRoot);
        if (!missing.isEmpty()) {
            throw new AssertionError("packages missing package-info.java:\n"
                    + missing.stream().map(Path::toString).collect(Collectors.joining("\n")));
        }
    }

    private static boolean containsJavaSource(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".java") && !name.equals("package-info.java");
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

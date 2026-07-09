package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageInfoChecksTest {

    @Test
    void reportsPackageMissingPackageInfo(@TempDir Path root) throws IOException {
        Path pkg = Files.createDirectories(root.resolve("com/example/domain"));
        Files.writeString(pkg.resolve("Foo.java"), "package com.example.domain; class Foo {}");

        assertEquals(1, PackageInfoChecks.packagesMissingPackageInfo(root).size());
        assertThrows(AssertionError.class,
                () -> PackageInfoChecks.assertEveryPackageHasPackageInfo(root));
    }

    @Test
    void passesWhenPackageInfoPresent(@TempDir Path root) throws IOException {
        Path pkg = Files.createDirectories(root.resolve("com/example/domain"));
        Files.writeString(pkg.resolve("Foo.java"), "package com.example.domain; class Foo {}");
        Files.writeString(pkg.resolve("package-info.java"), "package com.example.domain;");

        assertDoesNotThrow(() -> PackageInfoChecks.assertEveryPackageHasPackageInfo(root));
    }

    @Test
    void ignoresDirectoryWithoutJavaSources(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("com/example"));

        assertDoesNotThrow(() -> PackageInfoChecks.assertEveryPackageHasPackageInfo(root));
    }
}

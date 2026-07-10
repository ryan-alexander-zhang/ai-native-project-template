package com.example;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every source package declares a package-info.java. Checked at
 * source level (a package-info without annotations produces no class file and is
 * invisible to bytecode analysis). One source tree here, since the whole modular
 * monolith is a single Maven module.
 */
class PackageInfoTest {

    @Test
    void everyPackageHasPackageInfo() {
        PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
    }
}

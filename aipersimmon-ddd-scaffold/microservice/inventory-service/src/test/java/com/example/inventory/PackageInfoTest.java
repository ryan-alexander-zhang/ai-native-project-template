package com.example.inventory;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies every source package in this service declares a package-info.java. */
class PackageInfoTest {

    @Test
    void everyPackageHasPackageInfo() {
        PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
    }
}

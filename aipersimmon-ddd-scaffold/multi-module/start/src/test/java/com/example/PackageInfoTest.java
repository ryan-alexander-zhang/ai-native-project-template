package com.example;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every source package in the project declares a package-info.java.
 * Checked at source level (from the reactor root) because a package-info without
 * annotations produces no class file and is invisible to bytecode analysis.
 */
class PackageInfoTest {

    private static final List<String> MODULE_DIRS = List.of(
            "ordering/ordering-api",
            "ordering/ordering-domain",
            "ordering/ordering-application",
            "ordering/ordering-infrastructure",
            "ordering/ordering-adapter",
            "inventory/inventory-api",
            "inventory/inventory-domain",
            "inventory/inventory-application",
            "inventory/inventory-infrastructure",
            "inventory/inventory-adapter",
            "start");

    @Test
    void everyPackageHasPackageInfo() {
        for (String moduleDir : MODULE_DIRS) {
            Path sourceRoot = Path.of("..").resolve(moduleDir).resolve("src/main/java");
            PackageInfoChecks.assertEveryPackageHasPackageInfo(sourceRoot);
        }
    }
}

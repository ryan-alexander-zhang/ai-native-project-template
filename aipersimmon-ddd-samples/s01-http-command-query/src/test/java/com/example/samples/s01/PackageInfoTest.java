package com.example.samples.s01;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A source-level companion to the ArchUnit rules: package documentation cannot be checked from
 * bytecode, because a package-info without annotations leaves no class file.
 */
class PackageInfoTest {

  @Test
  void everyPackageIsDocumented() {
    PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
  }
}

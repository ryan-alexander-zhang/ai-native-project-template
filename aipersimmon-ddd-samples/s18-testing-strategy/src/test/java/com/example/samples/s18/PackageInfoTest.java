package com.example.samples.s18;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A source-level companion: a package-info without annotations leaves no class file to inspect. */
class PackageInfoTest {

  @Test
  void everyPackageIsDocumented() {
    PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
  }
}

package com.example.samples.s22.inventory;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Package documentation, checked over the sources. */
class PackageInfoTest {

  @Test
  void everyPackageIsDocumented() {
    PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
  }
}

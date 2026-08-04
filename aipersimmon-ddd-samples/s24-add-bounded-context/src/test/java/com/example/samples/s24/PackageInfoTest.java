package com.example.samples.s24;

import com.aipersimmon.ddd.archunit.PackageInfoChecks;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Package documentation, checked over the sources.
 *
 * <p>It earns its place in this scenario more than in the others: adding a bounded context adds five packages, and the
 * reason each exists is the thing a reader needs and the thing nobody writes down afterwards.
 */
class PackageInfoTest {

  @Test
  void everyPackageIsDocumented() {
    PackageInfoChecks.assertEveryPackageHasPackageInfo(Path.of("src/main/java"));
  }
}

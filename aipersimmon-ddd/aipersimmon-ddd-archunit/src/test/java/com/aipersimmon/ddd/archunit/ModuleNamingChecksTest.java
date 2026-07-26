package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The checker itself is exercised against hand-written poms, and then run over the real reactor —
 * both halves matter. Without the fixtures the assertion could be vacuously green; without the
 * reactor run it would not actually guard this repository.
 */
class ModuleNamingChecksTest {

  private static Path module(Path root, String artifactId, String dependencies) throws IOException {
    Path dir = Files.createDirectories(root.resolve(artifactId));
    Files.writeString(
        dir.resolve("pom.xml"),
        """
        <project>
          <parent>
            <groupId>com.aipersimmon.ddd</groupId>
            <artifactId>aipersimmon-ddd-parent</artifactId>
          </parent>
          <artifactId>%s</artifactId>
          <dependencies>
        %s
          </dependencies>
        </project>
        """
            .formatted(artifactId, dependencies));
    return dir;
  }

  private static String dependency(String groupId, String artifactId, String scope) {
    return """
        <dependency>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
        %s</dependency>
        """
        .formatted(groupId, artifactId, scope == null ? "" : "  <scope>" + scope + "</scope>\n");
  }

  @Test
  void aContractModuleNamingSpringIsAViolation(@TempDir Path root) throws IOException {
    module(
        root, "aipersimmon-ddd-outbox", dependency("org.springframework", "spring-context", null));

    List<String> violations = ModuleNamingChecks.contractModulesNamingAFramework(root);

    assertEquals(
        List.of("aipersimmon-ddd-outbox -> org.springframework:spring-context"), violations);
    assertThrows(AssertionError.class, () -> ModuleNamingChecks.assertModuleNamingRules(root));
  }

  @Test
  void thePluggableSuffixesAreExempt(@TempDir Path root) throws IOException {
    String spring = dependency("org.springframework.boot", "spring-boot-autoconfigure", null);
    for (String artifactId :
        List.of(
            "aipersimmon-ddd-inbox-jdbc",
            "aipersimmon-ddd-outbox-mybatis-plus",
            "aipersimmon-ddd-web-store-redis",
            "aipersimmon-ddd-messaging-kafka",
            "aipersimmon-ddd-process-manager-engine",
            "aipersimmon-ddd-cqrs-spring-boot-starter")) {
      module(root, artifactId, spring);
    }

    assertDoesNotThrow(() -> ModuleNamingChecks.assertModuleNamingRules(root));
  }

  @Test
  void aBundleIsAssemblyAndMayNameAFramework(@TempDir Path root) throws IOException {
    String spring = dependency("org.springframework.boot", "spring-boot-autoconfigure", null);
    module(root, "aipersimmon-ddd-starter", spring);
    module(root, "aipersimmon-ddd-starter-mybatis-plus", spring);

    assertDoesNotThrow(() -> ModuleNamingChecks.assertModuleNamingRules(root));
  }

  @Test
  void aContractModuleIsNotExcusedByMerelyContainingTheWordStarter(@TempDir Path root)
      throws IOException {
    // The bundle rule keys on the aipersimmon-ddd-starter prefix, not on the word appearing
    // anywhere, so it cannot be used to smuggle a framework into a contract module.
    module(
        root,
        "aipersimmon-ddd-my-starter-thing",
        dependency("org.springframework", "spring-tx", null));

    assertEquals(1, ModuleNamingChecks.contractModulesNamingAFramework(root).size());
  }

  @Test
  void aTestScopedFrameworkDependencyIsFine(@TempDir Path root) throws IOException {
    module(
        root,
        "aipersimmon-ddd-cqrs",
        dependency("org.springframework.boot", "spring-boot-starter-test", "test"));

    assertDoesNotThrow(() -> ModuleNamingChecks.assertModuleNamingRules(root));
  }

  @Test
  void mybatisPlusCountsAsAFrameworkForAContractModule(@TempDir Path root) throws IOException {
    module(
        root, "aipersimmon-ddd-core", dependency("com.baomidou", "mybatis-plus-extension", null));

    assertEquals(1, ModuleNamingChecks.contractModulesNamingAFramework(root).size());
  }

  @Test
  void theAbandonedSpringSuffixIsAViolationOnItsOwn(@TempDir Path root) throws IOException {
    module(root, "aipersimmon-ddd-cqrs-spring", "");

    assertEquals(
        List.of("aipersimmon-ddd-cqrs-spring"),
        ModuleNamingChecks.modulesWithALegacySpringSuffix(root));
    AssertionError error =
        assertThrows(AssertionError.class, () -> ModuleNamingChecks.assertModuleNamingRules(root));
    assertTrue(error.getMessage().contains("-spring-boot-starter"), "says what to rename it to");
  }

  @Test
  void aSpringBootStarterDoesNotTripTheLegacySuffixRule(@TempDir Path root) throws IOException {
    module(root, "aipersimmon-ddd-cqrs-spring-boot-starter", "");

    assertEquals(List.of(), ModuleNamingChecks.modulesWithALegacySpringSuffix(root));
  }

  /** The rule that matters: this repository obeys it. Run over the actual reactor. */
  @Test
  void everyModuleInThisReactorObeysTheNamingRules() {
    ModuleNamingChecks.assertModuleNamingRules(Path.of(".."));
  }
}

package com.aipersimmon.ddd.archunit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-time check that module names keep telling the truth about what a module is.
 *
 * <p>Two rules, both from {@code design-00012}:
 *
 * <ol>
 *   <li><strong>The invariant.</strong> A module whose artifactId carries no technology suffix is a
 *       <em>contract</em> module — the kind a domain layer may depend on — so it must not depend on
 *       a framework. Backend adapters ({@code -jdbc}, {@code -mybatis-plus}, {@code -redis}, {@code
 *       -kafka}), storage-agnostic runtimes ({@code -engine}) and assembly modules ({@code
 *       -spring-boot-starter}) are the pluggable modules the root pom exempts: a domain layer never
 *       names them.
 *   <li><strong>The naming discipline.</strong> No artifactId may end in {@code -spring}. One role
 *       had two suffixes ({@code -cqrs-spring} beside {@code -openapi-spring-boot-starter}), so a
 *       reader could not tell from a name what kind of module it was; that must not come back.
 * </ol>
 *
 * <p>This reads poms rather than bytecode because the thing being constrained is a <em>declared
 * dependency</em>. A contract module can compile perfectly well against a framework that arrived
 * transitively; what must not happen is that it <em>asks</em> for one.
 *
 * <pre>{@code
 * @Test
 * void module_names_tell_the_truth() {
 *     ModuleNamingChecks.assertModuleNamingRules(Path.of(".."));
 * }
 * }</pre>
 */
public final class ModuleNamingChecks {

  private ModuleNamingChecks() {}

  /** Suffixes that mark a module as pluggable, and therefore allowed to name a framework. */
  private static final List<String> PLUGGABLE_SUFFIXES =
      List.of("-jdbc", "-mybatis-plus", "-redis", "-kafka", "-engine", "-spring-boot-starter");

  /** Group-id prefixes a contract module must not ask for. */
  private static final List<String> FRAMEWORK_GROUPS =
      List.of("org.springframework", "com.baomidou");

  /**
   * Build tooling, exempt from rule 1 with reasons. These are not framework components at all: no
   * application depends on them at runtime, so "a domain layer may depend on it" never applies.
   *
   * <ul>
   *   <li>{@code -bom} — packaging {@code pom}, declares only {@code dependencyManagement}.
   *   <li>{@code -quality-config} — carries Spotless/PMD/SpotBugs configuration resources.
   *   <li>{@code -archunit} — the architecture rules themselves; it compiles against the framework
   *       in order to assert things about it, and consumers take it in {@code test} scope.
   *   <li>{@code -test-support} — Testcontainers/Spring-test helpers, taken in {@code test} scope.
   * </ul>
   */
  private static final Set<String> TOOLING =
      Set.of(
          "aipersimmon-ddd-bom",
          "aipersimmon-ddd-quality-config",
          "aipersimmon-ddd-archunit",
          "aipersimmon-ddd-test-support");

  private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
  private static final Pattern DEPENDENCY =
      Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
  private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
  private static final Pattern TEST_SCOPE = Pattern.compile("<scope>test</scope>");

  /**
   * Contract modules (no pluggable suffix) that declare a framework dependency outside {@code test}
   * scope, each reported as {@code artifactId -> groupId:artifactId}.
   *
   * @param reactorRoot the directory holding the module directories (the reactor pom's directory)
   */
  public static List<String> contractModulesNamingAFramework(Path reactorRoot) {
    List<String> violations = new ArrayList<>();
    for (Path pom : modulePoms(reactorRoot)) {
      String text = read(pom);
      String artifactId = moduleArtifactId(text);
      if (artifactId == null || TOOLING.contains(artifactId) || isPluggable(artifactId)) {
        continue;
      }
      for (String dependency : frameworkDependencies(text)) {
        violations.add(artifactId + " -> " + dependency);
      }
    }
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  /** Modules whose artifactId ends in the abandoned {@code -spring} suffix. */
  public static List<String> modulesWithALegacySpringSuffix(Path reactorRoot) {
    List<String> violations = new ArrayList<>();
    for (Path pom : modulePoms(reactorRoot)) {
      String artifactId = moduleArtifactId(read(pom));
      if (artifactId != null && artifactId.endsWith("-spring")) {
        violations.add(artifactId);
      }
    }
    return violations;
  }

  /** Fails with every violation of both rules listed, or returns quietly. */
  public static void assertModuleNamingRules(Path reactorRoot) {
    List<String> framework = contractModulesNamingAFramework(reactorRoot);
    List<String> legacy = modulesWithALegacySpringSuffix(reactorRoot);
    if (framework.isEmpty() && legacy.isEmpty()) {
      return;
    }
    StringBuilder message = new StringBuilder("module naming rules violated (design-00012)");
    if (!framework.isEmpty()) {
      message
          .append("\n\nContract modules must not declare a framework dependency — either drop it, ")
          .append("move the framework-facing code to a -spring-boot-starter module, or give the ")
          .append("module a pluggable suffix if that is what it really is:\n  - ")
          .append(String.join("\n  - ", framework));
    }
    if (!legacy.isEmpty()) {
      message
          .append(
              "\n\nThe -spring suffix was replaced by -spring-boot-starter so one role has one ")
          .append("name:\n  - ")
          .append(String.join("\n  - ", legacy));
    }
    throw new AssertionError(message.toString());
  }

  private static boolean isPluggable(String artifactId) {
    return PLUGGABLE_SUFFIXES.stream().anyMatch(artifactId::endsWith);
  }

  /** The {@code <artifactId>} of the module itself: the first one that is not the parent's. */
  private static String moduleArtifactId(String pom) {
    String withoutParent = pom.replaceFirst("(?s)<parent>.*?</parent>", "");
    Matcher matcher = ARTIFACT_ID.matcher(withoutParent);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static List<String> frameworkDependencies(String pom) {
    // Only the module's own <dependencies>; a BOM-style <dependencyManagement> declares nothing.
    String body = pom.replaceFirst("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
    List<String> found = new ArrayList<>();
    Matcher dependency = DEPENDENCY.matcher(body);
    while (dependency.find()) {
      String block = dependency.group(1);
      if (TEST_SCOPE.matcher(block).find()) {
        continue;
      }
      Matcher group = GROUP_ID.matcher(block);
      if (!group.find()) {
        continue;
      }
      String groupId = group.group(1);
      if (FRAMEWORK_GROUPS.stream().anyMatch(groupId::startsWith)) {
        Matcher artifact = ARTIFACT_ID.matcher(block);
        found.add(groupId + ":" + (artifact.find() ? artifact.group(1) : "?"));
      }
    }
    return found;
  }

  private static List<Path> modulePoms(Path reactorRoot) {
    if (!Files.isDirectory(reactorRoot)) {
      throw new IllegalArgumentException("not a directory: " + reactorRoot);
    }
    try (Stream<Path> children = Files.list(reactorRoot)) {
      return children
          .filter(Files::isDirectory)
          .map(dir -> dir.resolve("pom.xml"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path pom) {
    try {
      return Files.readString(pom);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

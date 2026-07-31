package com.aipersimmon.ddd.archunit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Build-time check that module names keep telling the truth about what a module is.
 *
 * <p>Two rules:
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

  /**
   * Bundles: {@code aipersimmon-ddd-starter} and {@code aipersimmon-ddd-starter-<stack>}. They
   * aggregate other modules into the two or three dependencies an application declares, so they are
   * assembly, not contract — a domain layer never names one. The {@code -starter-} infix keeps a
   * bundle distinguishable from a single-concern {@code <domain>-spring-boot-starter}, and keeps
   * the two from colliding over a name.
   */
  private static final String BUNDLE_PREFIX = "aipersimmon-ddd-starter";

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

  /**
   * Contract modules (no pluggable suffix) that declare a framework dependency outside {@code test}
   * scope, each reported as {@code artifactId -> groupId:artifactId}.
   *
   * @param reactorRoot the directory holding the module directories (the reactor pom's directory)
   */
  public static List<String> contractModulesNamingAFramework(Path reactorRoot) {
    List<String> violations = new ArrayList<>();
    for (Path pom : modulePoms(reactorRoot)) {
      Element project = parse(pom);
      String artifactId = moduleArtifactId(project);
      if (artifactId == null || TOOLING.contains(artifactId) || isPluggable(artifactId)) {
        continue;
      }
      for (String dependency : frameworkDependencies(project)) {
        violations.add(artifactId + " -> " + dependency);
      }
    }
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  /** Modules whose artifactId ends in the abandoned {@code -spring} suffix. */
  public static List<String> modulesWithALegacySpringSuffix(Path reactorRoot) {
    List<String> violations = new ArrayList<>();
    for (Path pom : modulePoms(reactorRoot)) {
      String artifactId = moduleArtifactId(parse(pom));
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
    StringBuilder message = new StringBuilder("module naming rules violated");
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
    return artifactId.equals(BUNDLE_PREFIX)
        || artifactId.startsWith(BUNDLE_PREFIX + "-")
        || PLUGGABLE_SUFFIXES.stream().anyMatch(artifactId::endsWith);
  }

  /** The {@code <artifactId>} of the module itself: the first one that is not the parent's. */
  /** The module's own artifactId — a direct child of {@code <project>}, not the parent's. */
  private static String moduleArtifactId(Element project) {
    return childrenNamed(project, "artifactId").stream()
        .findFirst()
        .map(Element::getTextContent)
        .map(String::trim)
        .orElse(null);
  }

  /**
   * Framework dependencies the module actually declares.
   *
   * <p>Only {@code <project><dependencies>} counts: a {@code <dependencyManagement>} block declares
   * nothing (it pins a version for whoever does declare it), and a dependency inside {@code
   * <profiles>} is not on by default. Reading the parsed document rather than the file's text is
   * what makes those distinctions available at all — and it is why a dependency someone commented
   * out no longer reads as a violation, which is the sort of finding that teaches people to
   * distrust the check.
   */
  private static List<String> frameworkDependencies(Element project) {
    List<String> found = new ArrayList<>();
    for (Element dependencies : childrenNamed(project, "dependencies")) {
      for (Element dependency : childrenNamed(dependencies, "dependency")) {
        if (text(dependency, "scope").filter("test"::equals).isPresent()) {
          continue;
        }
        String groupId = text(dependency, "groupId").orElse("");
        if (FRAMEWORK_GROUPS.stream().anyMatch(groupId::startsWith)) {
          found.add(groupId + ":" + text(dependency, "artifactId").orElse("?"));
        }
      }
    }
    return found;
  }

  private static java.util.Optional<String> text(Element parent, String name) {
    return childrenNamed(parent, name).stream()
        .findFirst()
        .map(Element::getTextContent)
        .map(String::trim);
  }

  /** Direct element children with the given tag name. Comments and text nodes are not elements. */
  private static List<Element> childrenNamed(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && name.equals(element.getTagName())) {
        found.add(element);
      }
    }
    return found;
  }

  private static Element parse(Path pom) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(Files.newInputStream(pom));
      return document.getDocumentElement();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SAXException | ParserConfigurationException e) {
      throw new IllegalStateException("cannot read " + pom, e);
    }
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
}

package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A BOM publishes what its owner promises to keep aligned. This one promises the versions of the
 * {@code com.aipersimmon.ddd} modules — and nothing about the Spring Boot line an application runs
 * on.
 *
 * <p>It used to promise far more than that by accident. An imported BOM contributes its
 * <em>effective</em> model, parent inheritance included, so inheriting {@code
 * aipersimmon-ddd-parent} republished every pin the library happens to build against: importing
 * this BOM alone brought roughly 1600 managed coordinates.
 *
 * <p>What made that more than untidy is who it reached. Between two imported BOMs the first import
 * wins, and this library tells consumers to import this one <em>before</em> {@code
 * spring-boot-dependencies} — its own scaffold does — because it carries the OpenTelemetry line the
 * observability starter needs. So an application that imports BOMs rather than inheriting {@code
 * spring-boot-starter-parent} had its own choice of Spring Boot silently overruled by ours, in
 * exactly the arrangement this library recommends. (An application inheriting {@code
 * spring-boot-starter-parent} was never affected: in Maven, inherited management outranks an
 * imported BOM.)
 *
 * <p>None of it was visible in the file — the leak lived in a {@code <parent>} element — and the
 * BOM even re-declared springdoc "so consumers align without inheriting the parent", written by
 * someone who believed the rest did not travel. So it is asserted here rather than left to be
 * noticed again.
 */
class BomExportsOnlyItsOwnModulesTest {

  /**
   * Third-party coordinates this BOM re-exports on purpose. Each is a version the library's own
   * code will not work without — the OpenTelemetry core line its instrumentation is built against,
   * and the OpenAPI artifacts nothing else manages — as opposed to a version it merely happened to
   * compile against.
   */
  private static final Set<String> DELIBERATE_RE_EXPORTS =
      Set.of("io.opentelemetry", "org.springdoc", "io.swagger.core.v3");

  /** BOM version literal → the parent property that must agree with it. */
  private static final Map<String, String> LITERALS_TO_KEEP_IN_STEP =
      Map.of(
          "io.opentelemetry:opentelemetry-bom", "opentelemetry.version",
          "org.springdoc:springdoc-openapi-starter-webmvc-ui", "springdoc.version",
          "io.swagger.core.v3:swagger-annotations-jakarta", "swagger.version");

  private final Path reactorRoot = reactorRoot();

  @Test
  void theBomHasNoParentBecauseAParentIsExactlyWhatLeaks() throws Exception {
    Element project = parse(reactorRoot.resolve("aipersimmon-ddd-bom/pom.xml"));

    assertEquals(
        0,
        childrenNamed(project, "parent").size(),
        "aipersimmon-ddd-bom must not inherit a parent: an imported BOM contributes its effective "
            + "model, so a parent's dependencyManagement becomes part of what every consumer of "
            + "this BOM is pinned to");
    assertEquals(
        "com.aipersimmon.ddd",
        text(childrenNamed(project, "groupId").get(0)),
        "with no parent to inherit them from, coordinates are declared here");
  }

  @Test
  void nothingIsManagedThatThisLibraryDoesNotPromiseToAlign() throws Exception {
    List<String> foreign = new ArrayList<>();
    for (Map.Entry<String, String> managed : managedByTheBom().entrySet()) {
      String groupId = managed.getKey().substring(0, managed.getKey().indexOf(':'));
      if (!"com.aipersimmon.ddd".equals(groupId) && !DELIBERATE_RE_EXPORTS.contains(groupId)) {
        foreign.add(managed.getKey());
      }
    }

    assertTrue(
        foreign.isEmpty(),
        "the BOM manages third-party coordinates it makes no promise about: "
            + foreign
            + ". Consumers import this BOM before spring-boot-dependencies (it has to precede it "
            + "for the OpenTelemetry line), and the first import wins — so anything managed here "
            + "overrules their own choice. Add the groupId to DELIBERATE_RE_EXPORTS only if the "
            + "library genuinely does not work on another version of it");
  }

  @Test
  void theVersionLiteralsStillMatchTheParentTheyWereCopiedFrom() throws Exception {
    Map<String, String> managed = managedByTheBom();
    Map<String, String> parentProperties = properties(parse(reactorRoot.resolve("pom.xml")));

    LITERALS_TO_KEEP_IN_STEP.forEach(
        (coordinate, property) ->
            assertEquals(
                parentProperties.get(property),
                managed.get(coordinate),
                "having no parent means these versions are written twice; "
                    + coordinate
                    + " in the BOM has drifted from "
                    + property
                    + " in aipersimmon-ddd-parent. Update both, or the library will be built "
                    + "against one version and consumers aligned to another"));

    assertEquals(
        text(childrenNamed(parse(reactorRoot.resolve("pom.xml")), "version").get(0)),
        text(
            childrenNamed(parse(reactorRoot.resolve("aipersimmon-ddd-bom/pom.xml")), "version")
                .get(0)),
        "the BOM's own version is written twice for the same reason, and a release that bumps "
            + "only one of them publishes a BOM pointing at modules that do not exist");
  }

  /**
   * Managed coordinates of the BOM, {@code groupId:artifactId} to version, as literally written.
   */
  private Map<String, String> managedByTheBom() throws Exception {
    Element project = parse(reactorRoot.resolve("aipersimmon-ddd-bom/pom.xml"));
    Element management = childrenNamed(project, "dependencyManagement").get(0);
    Element dependencies = childrenNamed(management, "dependencies").get(0);
    Map<String, String> managed = new LinkedHashMap<>();
    for (Element dependency : childrenNamed(dependencies, "dependency")) {
      managed.put(
          text(childrenNamed(dependency, "groupId").get(0))
              + ":"
              + text(childrenNamed(dependency, "artifactId").get(0)),
          text(childrenNamed(dependency, "version").get(0)));
    }
    return managed;
  }

  private static Map<String, String> properties(Element project) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (Element block : childrenNamed(project, "properties")) {
      for (Element property : childrenNamed(block, null)) {
        properties.put(property.getTagName(), text(property));
      }
    }
    return properties;
  }

  private static Element parse(Path pom)
      throws IOException, SAXException, ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(Files.newInputStream(pom));
    return document.getDocumentElement();
  }

  /** Direct element children with the given tag name, or all of them when {@code name} is null. */
  private static List<Element> childrenNamed(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && (name == null || name.equals(element.getTagName()))) {
        found.add(element);
      }
    }
    return found;
  }

  private static String text(Element element) {
    return element.getTextContent().trim();
  }

  /** The reactor root, found by walking up from this module rather than assumed. */
  private static Path reactorRoot() {
    for (Path candidate = Path.of("").toAbsolutePath();
        candidate != null;
        candidate = candidate.getParent()) {
      if (Files.exists(candidate.resolve("aipersimmon-ddd-bom/pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("no reactor root above " + Path.of("").toAbsolutePath());
  }
}

package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * How many application contexts this module's tests ask for, pinned.
 *
 * <p>The containers are Spring beans rather than static fields (see {@link TestInfrastructure}), so
 * each distinct context gets its own PostgreSQL and its own Kafka. That is a deliberate trade and a
 * good one: because every context owns its database, a test may assert over a whole table — {@code
 * OutboxAtomicityTest} checks {@code select count(*) from ordering.orders == 0}, which a shared
 * database would make meaningless. What it costs is a container pair per context, each starting a
 * broker and running eight Flyway migrations.
 *
 * <p>The cost is invisible at the point where it is incurred. Spring caches a context under a key
 * built from properties, web environment, imported configuration, bean overrides and more, and that
 * key is never printed. Adding one property to one test class silently buys another container pair;
 * nothing in the diff says so. {@code SelfCancelTest} carries a comment noting that its properties
 * match two sibling classes exactly so all three share one context — true, load-bearing, and only
 * enforced by whoever remembers to read it.
 *
 * <p>So this test computes the real key. {@link MergedContextConfiguration} <em>is</em> the cache
 * key — Spring's own {@code ContextCache} looks contexts up by it — and building one starts
 * nothing. When the number changes, the failure names the groups, and the reader decides whether
 * the new context was worth its containers rather than discovering it in a slower build.
 */
class TestContextCountTest {

  /**
   * Raise or lower this deliberately. A new distinct context means another PostgreSQL and another
   * Kafka for the length of the build; joining an existing group costs nothing. If a change here is
   * intended, the README's note on what {@code mvn verify} starts should move with it.
   *
   * <p>Sixteen of the seventeen bring a container pair. {@code ProductionProfileBootTest} is the
   * exception: it takes a raw container from {@code SharedContainers} and starts no broker at all,
   * for reasons its own javadoc gives. The review that first asked for this pin estimated nine to
   * eleven by reading the test sources — the gap between that estimate and this number is the
   * argument for computing the key rather than eyeballing it.
   */
  private static final int EXPECTED_CONTEXTS = 17;

  @Test
  void theNumberOfDistinctTestContextsIsDeliberate() {
    Map<String, List<String>> contexts = new TreeMap<>();
    for (Class<?> testClass : springBootTestClasses()) {
      contexts
          .computeIfAbsent(cacheKeyOf(testClass), key -> new ArrayList<>())
          .add(testClass.getSimpleName());
    }

    assertEquals(
        EXPECTED_CONTEXTS,
        contexts.size(),
        () ->
            "the number of distinct test contexts changed, and all but one of them start their"
                + " own PostgreSQL + Kafka pair. Groups now:\n"
                + describe(contexts)
                + "If this is intended, update EXPECTED_CONTEXTS and the README. If it is not,"
                + " the usual cause is a property added to one class that used to match its"
                + " neighbours exactly — matching them again puts it back in their group.");
  }

  /**
   * Every class the TestContext framework would build a context for: those carrying {@link
   * SpringBootTest}, plus {@link Nested} classes inside them, which inherit the enclosing
   * configuration and can still split off a context of their own by overriding properties.
   */
  private static List<Class<?>> springBootTestClasses() {
    List<Class<?>> found = new ArrayList<>();
    for (JavaClass imported : new ClassFileImporter().importPackages("com.example")) {
      Class<?> candidate = imported.reflect();
      boolean annotated = candidate.isAnnotationPresent(SpringBootTest.class);
      boolean nestedInOne =
          candidate.isAnnotationPresent(Nested.class)
              && candidate.getEnclosingClass() != null
              && candidate.getEnclosingClass().isAnnotationPresent(SpringBootTest.class);
      if (annotated || nestedInOne) {
        found.add(candidate);
      }
    }
    return found;
  }

  /**
   * Spring's own cache key for this test class, built without loading a context. Rendered to a
   * string only so equal keys group together in a sorted map — the equality that matters is {@link
   * MergedContextConfiguration}'s.
   */
  private static String cacheKeyOf(Class<?> testClass) {
    MergedContextConfiguration configuration =
        BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
    return configuration.hashCode()
        + " "
        + new TreeSet<>(List.of(configuration.getPropertySourceProperties()));
  }

  private static String describe(Map<String, List<String>> contexts) {
    StringBuilder rendered = new StringBuilder();
    int index = 1;
    for (Map.Entry<String, List<String>> group : contexts.entrySet()) {
      rendered.append("  ").append(index++).append(". ").append(group.getValue()).append('\n');
    }
    return rendered.toString();
  }
}

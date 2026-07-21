package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Cross-context isolation: each bounded context may depend on another only through that context's
 * {@code ..api..} published contract. Parameterised on the base package, so it is
 * <strong>not</strong> part of the parameterless {@link AiPersimmonDddRules#all()}.
 */
public final class BoundedContextRules {

  private BoundedContextRules() {}

  /**
   * Each bounded context under {@code basePackage} depends on another context only through that
   * context's {@code ..api..} package — never by reaching into its domain, application,
   * infrastructure, or adapter internals. A context is the first package segment under {@code
   * basePackage} (so under {@code "com.example"} the contexts are {@code com.example.ordering},
   * {@code com.example.inventory}, …), and its {@code ..api..} package is its published contract;
   * everything else is private to it. This is the multi-context isolation rule that keeps the
   * "published language" boundary honest.
   *
   * <p>Parameterised on {@code basePackage}, so it is <strong>not</strong> part of the
   * parameterless {@link AiPersimmonDddRules#all()}; wire it into a test that also scopes which
   * classes are analysed. Only analysed classes are checked, so keep the composition root (which
   * legitimately wires every context together) out of {@code @AnalyzeClasses}, or it will be
   * reported. A class that sits directly in {@code basePackage} (an application root, with no
   * context segment) is skipped.
   *
   * <pre>{@code
   * @ArchTest static final ArchRule contexts =
   *         BoundedContextRules.boundedContextsShouldOnlyDependOnEachOthersApi("com.example");
   * }</pre>
   *
   * @param basePackage the package under which each immediate sub-package is a context
   */
  public static ArchRule boundedContextsShouldOnlyDependOnEachOthersApi(String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + "..")
        .should(dependOnOtherContextsOnlyThroughApi(basePackage))
        .as("bounded contexts should depend on each other only through their ..api.. packages")
        .because(
            "a context's internals are private; only its ..api.. package is the published "
                + "contract that other contexts may depend on")
        .allowEmptyShould(true);
  }

  /**
   * Reports a violation for each dependency whose target lives in a <em>different</em> bounded
   * context (a different first segment under {@code basePackage}) and is not in that context's
   * {@code ..api..} package. Dependencies within the same context, on the target context's {@code
   * ..api..}, or on anything outside {@code basePackage} (the JDK, frameworks, shared kernel) are
   * allowed.
   */
  private static ArchCondition<JavaClass> dependOnOtherContextsOnlyThroughApi(String basePackage) {
    String prefix = basePackage.endsWith(".") ? basePackage : basePackage + ".";
    return new ArchCondition<>(
        "depend on other bounded contexts only through their ..api.. packages") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        String originContext = contextSegment(origin.getName(), prefix);
        if (originContext == null) {
          return;
        }
        origin
            .getDirectDependenciesFromSelf()
            .forEach(
                dependency -> {
                  JavaClass target = dependency.getTargetClass();
                  String targetContext = contextSegment(target.getName(), prefix);
                  if (targetContext == null || targetContext.equals(originContext)) {
                    return;
                  }
                  String apiPackage = prefix + targetContext + ".api";
                  String targetPackage = target.getPackageName();
                  boolean throughApi =
                      targetPackage.equals(apiPackage)
                          || targetPackage.startsWith(apiPackage + ".");
                  if (!throughApi) {
                    events.add(
                        SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                  }
                });
      }
    };
  }

  /**
   * The bounded-context segment of {@code className}: the first package segment after {@code
   * prefix}, or {@code null} when the class does not live under {@code prefix} or sits directly in
   * it (no context segment).
   */
  private static String contextSegment(String className, String prefix) {
    if (!className.startsWith(prefix)) {
      return null;
    }
    String remainder = className.substring(prefix.length());
    int dot = remainder.indexOf('.');
    return dot < 0 ? null : remainder.substring(0, dot);
  }
}

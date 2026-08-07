package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * Determinism rules: the inner tiers read time and randomness from something injected — a {@code
 * Clock}, an {@code IdGenerator} — rather than from the ambient environment.
 *
 * <p>{@link #domainShouldNotUseAmbientTimeOrRandomness()} is bundled into {@link
 * AiPersimmonDddRules#all()}; {@link #applicationShouldNotUseAmbientTimeOrRandomness()} is opt-in,
 * because an application-tier class that is <em>not</em> a use-case handler (a scheduled cleanup, a
 * one-shot maintenance task) sometimes reads the wall clock legitimately, and a project should
 * decide for itself whether it wants that pinned too.
 *
 * <p>What counts as ambient is the <em>absence of a parameter that supplies the value</em>, not the
 * type being touched: {@code LocalDate.now()} and {@code LocalDate.now(zone)} both read the system
 * clock and are reported, while {@code LocalDate.now(clock)} takes its instant from a collaborator
 * and is allowed. Likewise {@code new Date(epochMillis)} and {@code new Random(seed)} are fine —
 * the caller supplied the value — and their no-argument forms are not.
 */
public final class DeterminismRules {

  /**
   * Zero-argument static methods that read the ambient environment, keyed by owning type. Matched
   * by name so this jar needs no import of any of them.
   */
  private static final String JAVA_TIME_PREFIX = "java.time.";

  private static final String CLOCK = "java.time.Clock";

  /** Types whose no-argument constructor captures the current time or a fresh random seed. */
  private static final Set<String> AMBIENT_NO_ARG_CONSTRUCTORS =
      Set.of("java.util.Date", "java.util.Random", "java.util.GregorianCalendar");

  /** Types whose every constructor is a fresh entropy source, seeded or not. */
  private static final Set<String> ALWAYS_AMBIENT_CONSTRUCTORS =
      Set.of("java.security.SecureRandom");

  /** Ambient static calls, spelled {@code owner#method}. */
  private static final Set<String> AMBIENT_STATIC_CALLS =
      Set.of(
          "java.lang.System#currentTimeMillis",
          "java.lang.System#nanoTime",
          "java.util.UUID#randomUUID",
          "java.lang.Math#random",
          "java.util.Calendar#getInstance",
          "java.util.concurrent.ThreadLocalRandom#current",
          "java.time.Clock#systemUTC",
          "java.time.Clock#systemDefaultZone",
          "java.time.Clock#system");

  private DeterminismRules() {}

  /**
   * No domain class reads the ambient clock or a fresh random source: no {@code Instant.now()},
   * {@code LocalDate.now()}, {@code System.currentTimeMillis()}, {@code new Date()}, {@code
   * UUID.randomUUID()}, {@code Math.random()}, {@code new Random()}.
   *
   * <p>An aggregate that stamps itself with {@code Instant.now()} cannot be tested at a chosen
   * instant, and one that mints its own {@code UUID.randomUUID()} produces a different object on
   * every replay of the same input — which is what makes "the same command twice" and "rebuild this
   * aggregate from its rows" stop being answerable. Time enters through a {@code Clock} and
   * identity through {@code IdGenerator}, both passed in; the caller decides what "now" is.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project
   * whose domain already takes both from the outside.
   */
  public static ArchRule domainShouldNotUseAmbientTimeOrRandomness() {
    return classes()
        .that()
        .resideInAPackage("..domain..")
        .should(notUseAmbientTimeOrRandomness())
        .as("domain classes should not read ambient time or randomness")
        .because(
            "an aggregate that stamps itself with the system clock cannot be tested at a chosen "
                + "instant, and one that mints its own random id yields a different object on "
                + "every replay; time arrives through an injected Clock and identity through "
                + "IdGenerator")
        .allowEmptyShould(true);
  }

  /**
   * The same check over the application layer: a use-case handler takes its instant from a {@code
   * Clock} and its identifiers from {@code IdGenerator} rather than reading them off the
   * environment.
   *
   * <p>Deliberately <strong>not</strong> part of {@link AiPersimmonDddRules#all()}. The application
   * tier also holds classes that are not use cases — a retention sweep, a scheduled reconciliation
   * — for which "whatever the wall clock says at this moment" is the actual input, and a project
   * may reasonably keep those on {@code Instant.now()}. Adopt it alongside {@link
   * AiPersimmonDddRules#all()} where the whole tier should be time-injected:
   *
   * <pre>{@code
   * @ArchTest static final ArchRule determinism =
   *         DeterminismRules.applicationShouldNotUseAmbientTimeOrRandomness();
   * }</pre>
   */
  public static ArchRule applicationShouldNotUseAmbientTimeOrRandomness() {
    return classes()
        .that()
        .resideInAPackage("..application..")
        .should(notUseAmbientTimeOrRandomness())
        .as("application classes should not read ambient time or randomness")
        .because(
            "a use case that reads the system clock or mints a random id in place cannot be "
                + "replayed or tested at a chosen instant; both arrive through an injected "
                + "collaborator")
        .allowEmptyShould(true);
  }

  /**
   * Reports every call that takes time or randomness from the environment instead of from an
   * argument. Shared by the two rules above and by {@link
   * ProcessRules#processDefinitionsShouldBePure()}, which needs the identical notion for a
   * different set of classes.
   *
   * <p>Phrased negatively and used with {@code classes().should(...)}, the same way the other
   * call-site conditions in this package are, so a {@code violated} event is a rule violation.
   */
  static ArchCondition<JavaClass> notUseAmbientTimeOrRandomness() {
    return new ArchCondition<>("not read ambient time or randomness") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
          if (isAmbientCall(call)) {
            events.add(SimpleConditionEvent.violated(call, call.getDescription()));
          }
        }
        for (JavaConstructorCall call : origin.getConstructorCallsFromSelf()) {
          if (isAmbientConstruction(call)) {
            events.add(SimpleConditionEvent.violated(call, call.getDescription()));
          }
        }
      }
    };
  }

  /**
   * A method call that reads the environment: one of the {@linkplain #AMBIENT_STATIC_CALLS named
   * static entry points}, or a {@code java.time} {@code now(..)} that was not handed a {@code
   * Clock}. The {@code Clock} overload is the whole point of the exception — {@code
   * LocalDate.now(zone)} still reads the system clock and is reported, {@code LocalDate.now(clock)}
   * does not and is not.
   */
  private static boolean isAmbientCall(JavaMethodCall call) {
    String owner = call.getTargetOwner().getName();
    String name = call.getTarget().getName();
    if (AMBIENT_STATIC_CALLS.contains(owner + '#' + name)) {
      return true;
    }
    if (!owner.startsWith(JAVA_TIME_PREFIX) || !name.equals("now")) {
      return false;
    }
    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
    return parameters.size() != 1 || !parameters.get(0).getName().equals(CLOCK);
  }

  /**
   * A constructor call that captures the environment: {@code new SecureRandom(..)} in any form, or
   * the no-argument {@code new Date()} / {@code new Random()} / {@code new GregorianCalendar()}.
   * The argument-taking forms of the latter three are deterministic — the caller supplied the epoch
   * millis or the seed — so they are allowed.
   */
  private static boolean isAmbientConstruction(JavaConstructorCall call) {
    String owner = call.getTargetOwner().getName();
    if (ALWAYS_AMBIENT_CONSTRUCTORS.contains(owner)) {
      return true;
    }
    return AMBIENT_NO_ARG_CONSTRUCTORS.contains(owner)
        && call.getTarget().getRawParameterTypes().isEmpty();
  }
}

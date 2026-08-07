package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

/**
 * Persistence-mapping rules: the row types and the mapper interfaces that talk to them are the
 * infrastructure layer's private vocabulary, and stay there.
 *
 * <p>{@link #persistenceMappingsShouldStayInInfrastructure()} is bundled into {@link
 * AiPersimmonDddRules#all()}. Everything is matched by fully-qualified <em>name</em>, so this jar
 * carries no compile dependency on MyBatis or MyBatis-Plus; a project on another persistence
 * technology has no matching classes and passes vacuously — and can state the same rule for its own
 * markers.
 */
public final class PersistenceRules {

  /** MyBatis' mapper stereotype. */
  private static final String IBATIS_MAPPER = "org.apache.ibatis.annotations.Mapper";

  /** MyBatis-Plus' generic mapper base. */
  private static final String MYBATIS_PLUS_BASE_MAPPER =
      "com.baomidou.mybatisplus.core.mapper.BaseMapper";

  /** MyBatis-Plus' table mapping, which is what makes a class a row rather than a model type. */
  private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";

  private PersistenceRules() {}

  /**
   * Mappers and mapped row types reside in the infrastructure layer, and nothing outside it depends
   * on them.
   *
   * <p>A row type is the table's shape, not the model's: it is flat because the table is flat, it
   * is mutable because the mapping framework sets its fields, and it changes whenever a migration
   * changes a column. That makes it exactly the wrong thing for anything else to hold. The moment
   * an application class takes an {@code OrderDo} or a {@code Mapper}, a schema change stops being
   * an infrastructure change: the repository port it was supposed to go through — the one
   * abstraction that survives replacing the persistence technology — has been bypassed, and the
   * compiler will never say so.
   *
   * <p>Related to but distinct from {@link
   * RepositoryRules#implementationsShouldResideInInfrastructure()}: that one places the class that
   * <em>fulfils</em> a port; this one places the machinery that class is built from, which no port
   * mentions and which therefore nothing else has a reason to see.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that
   * uses neither MyBatis nor MyBatis-Plus.
   */
  public static ArchRule persistenceMappingsShouldStayInInfrastructure() {
    return CompositeArchRule.of(mappersAndRowsShouldResideInInfrastructure())
        .and(nothingOutsideInfrastructureShouldDependOnMappersOrRows())
        .as(
            "mappers and mapped row types should reside in the infrastructure layer, and nothing "
                + "outside it should depend on them");
  }

  /**
   * One half of {@link #persistenceMappingsShouldStayInInfrastructure()}: a mapper interface or a
   * table-mapped row type is declared in {@code ..infrastructure..}. Exposed separately so a
   * project can state that half on its own.
   */
  public static ArchRule mappersAndRowsShouldResideInInfrastructure() {
    return classes()
        .that(arePersistenceMappings())
        .should()
        .resideInAPackage("..infrastructure..")
        .as("mappers and mapped row types should reside in the infrastructure layer")
        .because(
            "a mapper and a row are the table's shape and the technology's API, which is the "
                + "outbound adapter's business and nobody else's")
        .allowEmptyShould(true);
  }

  /**
   * The other half of {@link #persistenceMappingsShouldStayInInfrastructure()}: no class outside
   * {@code ..infrastructure..} depends on a mapper or a mapped row type. Exposed separately so a
   * project can state that half on its own.
   *
   * <p>This is the half with teeth. The placement rule is usually satisfied by habit — the row is
   * written next to the mapper that reads it — while the leak happens at the other end, when an
   * inbound adapter reaches for the mapper because it is quicker than adding a method to the port.
   *
   * <p><strong>Named layers, not "everywhere except infrastructure".</strong> Written the broad
   * way, this reported the multi-module scaffold's composition root, which takes a mapper as a
   * {@code @Bean} method parameter in order to construct an infrastructure component — and that is
   * assembly, the one job whose whole point is knowing every module's concrete types. Listing the
   * layers that must not know leaves the composition root out by construction, because it has no
   * layer segment. The domain and application arms are already implied by {@link
   * LayeringRules#domainShouldNotDependOnOuterLayers()} and its application counterpart <em>while
   * the mapping sits in infrastructure</em>; they are stated again here so the rule still holds for
   * a mapper that was declared in the wrong place to begin with.
   */
  public static ArchRule nothingOutsideInfrastructureShouldDependOnMappersOrRows() {
    return noClasses()
        .that()
        .resideInAnyPackage(Layers.INNER_AND_INTERFACE_LAYERS)
        .should()
        .dependOnClassesThat(arePersistenceMappings())
        .as("domain, application and interface classes should not depend on mappers or row types")
        .because(
            "reaching for the mapper instead of adding a method to the repository port turns the "
                + "next migration from an infrastructure change into a change that spreads")
        .allowEmptyShould(true);
  }

  /**
   * A persistence mapping: a MyBatis {@code @Mapper}, a MyBatis-Plus {@code BaseMapper} subtype, or
   * a {@code @TableName}-mapped row. Matched by name, so the rule costs no dependency and a project
   * on another technology simply matches nothing.
   */
  private static DescribedPredicate<JavaClass> arePersistenceMappings() {
    return DescribedPredicate.describe(
        "are mappers or mapped row types",
        javaClass ->
            javaClass.isAnnotatedWith(IBATIS_MAPPER)
                || javaClass.isAnnotatedWith(TABLE_NAME)
                || (javaClass.isAssignableTo(MYBATIS_PLUS_BASE_MAPPER)
                    && !javaClass.getName().equals(MYBATIS_PLUS_BASE_MAPPER)));
  }
}

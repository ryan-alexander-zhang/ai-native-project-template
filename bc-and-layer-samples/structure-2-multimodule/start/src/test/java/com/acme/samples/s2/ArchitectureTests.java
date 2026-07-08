package com.acme.samples.s2;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Backstops the boundaries the Maven module graph already enforces at compile
 * time: framework-free domain, and cross-context access only via {@code *-api}.
 */
@AnalyzeClasses(packages = "com.acme.samples.s2", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "com.baomidou..", "org.apache.ibatis..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule ordering_touches_inventory_only_via_api = noClasses()
            .that().resideInAPackage("..ordering..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..inventory.domain..", "..inventory.application..",
                    "..inventory.infrastructure..", "..inventory.adapter..");

    @ArchTest
    static final ArchRule inventory_touches_ordering_only_via_api = noClasses()
            .that().resideInAPackage("..inventory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..ordering.domain..", "..ordering.application..",
                    "..ordering.infrastructure..", "..ordering.adapter..");
}

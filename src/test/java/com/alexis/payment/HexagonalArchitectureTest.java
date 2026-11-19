package com.alexis.payment;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The rules of CLAUDE.md, executed: an agent (or a human) breaking them fails the build. */
@AnalyzeClasses(packages = "com.alexis.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_framework_agnostic = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..", "org.hibernate..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_adapters = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..");

    @ArchTest
    static final ArchRule inbound_adapters_do_not_reach_outbound_adapters = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule only_psp_adapters_use_the_payload_cipher = noClasses()
            .that().resideOutsideOfPackages("..adapter.out.psp..", "..adapter.out.crypto..", "..config..")
            .should().dependOnClassesThat().haveSimpleName("AesGcmPayloadCipher");

    @ArchTest
    static final ArchRule no_floating_point_money = noClasses()
            .that().resideInAPackage("..domain.model..")
            .should().accessClassesThat().haveFullyQualifiedName("java.lang.Double")
            .orShould().accessClassesThat().haveFullyQualifiedName("java.lang.Float");
}

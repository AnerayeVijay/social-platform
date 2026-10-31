package com.example.social.architecture

import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(packages = ["com.example.social"])
class DependencyRulesTest {
    @ArchTest
    val `shared kernel depends on no other module` = noClasses()
        .that().resideInAPackage("com.example.social.sharedkernel..")
        .should().dependOnClassesThat().resideOutsideOfPackages("com.example.social.sharedkernel..", "java..", "kotlin..", "org.jetbrains.annotations..")
        .allowEmptyShould(false)
}

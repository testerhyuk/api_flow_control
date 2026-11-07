package com.hyuk.flow_control.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class LayerDependencyTest {

    private static final String BASE = "com.hyuk.flow_control";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("domain 은 application 과 adapter 를 모른다")
    void domainDoesNotDependOnOuterLayers() {
        noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        BASE + ".application..",
                        BASE + ".adapter..",
                        BASE + ".config.."
                )
                .check(classes);
    }

    @Test
    @DisplayName("domain 은 프레임워크를 모른다")
    void domainDoesNotDependOnFramework() {
        noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.apache.kafka..",
                        "jakarta.persistence..",
                        "lombok.."
                )
                .check(classes);
    }

    @Test
    @DisplayName("application 은 adapter 를 모른다")
    void applicationDoesNotDependOnAdapter() {
        noClasses()
                .that().resideInAPackage(BASE + ".application..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("adapter 는 application.service 를 모르고 port 로만 쓴다")
    void adapterUsesOnlyPorts() {
        noClasses()
                .that().resideInAPackage(BASE + ".adapter..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".application.service..")
                .check(classes);
    }

    @Test
    @DisplayName("application 은 config 를 모른다")
    void applicationDoesNotDependOnConfig() {
        noClasses()
                .that().resideInAPackage(BASE + ".application..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".config..")
                .check(classes);
    }

    @Test
    @DisplayName("adapter.out 의 구현체들은 서로를 모른다")
    void outboundAdaptersDoNotDependOnEachOther() {
        slices()
                .matching(BASE + ".adapter.out.(*)..")
                .should().notDependOnEachOther()
                .check(classes);
    }
}
package com.shanshui.apmserver;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块化重构期间的架构基线。旧技术分层违规在迁移前作为已知事实记录，
 * 新业务模块从创建之日起立即受目标依赖规则约束。
 */
class ArchitectureBaselineTests {

    private static final String ROOT = "com.shanshui.apmserver.";
    private static final Set<String> MODULES = Set.of(
            "bootstrap", "identity", "telemetry", "ingest", "crash", "jank", "memory", "symbol", "platform", "agentquery");
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.of(
            "bootstrap", Set.of("identity", "telemetry", "ingest", "crash", "jank", "memory", "symbol", "platform"),
            "identity", Set.of("platform"),
            "telemetry", Set.of("platform"),
            "ingest", Set.of("identity", "telemetry", "crash", "jank", "memory", "platform"),
            "crash", Set.of("identity", "telemetry", "symbol", "platform"),
            "jank", Set.of("identity", "telemetry", "symbol", "platform"),
            "memory", Set.of("identity", "telemetry", "platform"),
            "symbol", Set.of("identity", "platform"),
            "agentquery", Set.of("identity", "crash", "jank", "memory", "platform"),
            "platform", Set.of());

    private static final JavaClasses APPLICATION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.shanshui.apmserver");

    @Test
    void scansApplicationClasses() {
        assertThat(APPLICATION_CLASSES).isNotEmpty();
    }

    @Test
    void technicalLayerReverseDependenciesAreRemoved() {
        ArchRule serviceMustNotDependOnWeb = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..web..")
                .allowEmptyShould(true);
        ArchRule repositoryMustNotDependOnService = noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..service..")
                .allowEmptyShould(true);

        assertThat(serviceMustNotDependOnWeb.evaluate(APPLICATION_CLASSES).hasViolation()).isFalse();
        assertThat(repositoryMustNotDependOnService.evaluate(APPLICATION_CLASSES).hasViolation()).isFalse();
    }

    @Test
    void newModuleBoundariesAreRespectedDuringMigration() {
        Set<String> violations = new LinkedHashSet<>();
        for (JavaClass source : APPLICATION_CLASSES) {
            String sourceModule = moduleOf(source.getName());
            if (sourceModule == null) {
                continue;
            }
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                String targetName = dependency.getTargetClass().getName();
                String targetModule = moduleOf(targetName);
                if (targetModule == null || sourceModule.equals(targetModule)) {
                    continue;
                }
                if (!ALLOWED_DEPENDENCIES.get(sourceModule).contains(targetModule)) {
                    violations.add(source.getName() + " 不允许依赖 " + targetName);
                }
                if (targetName.startsWith(ROOT + targetModule + ".internal.")) {
                    violations.add(source.getName() + " 不得访问内部类型 " + targetName);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static String moduleOf(String className) {
        if (!className.startsWith(ROOT)) {
            return null;
        }
        String relativeName = className.substring(ROOT.length());
        int separator = relativeName.indexOf('.');
        String candidate = separator < 0 ? relativeName : relativeName.substring(0, separator);
        return MODULES.contains(candidate) ? candidate : null;
    }
}

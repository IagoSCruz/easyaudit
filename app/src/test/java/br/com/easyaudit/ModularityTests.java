package br.com.easyaudit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private static final DescribedPredicate<JavaClass> PRODUCTION_CLASSES_ONLY =
            DescribedPredicate.describe("production classes only",
                    clazz -> !clazz.getSimpleName().endsWith("Test")
                            && !clazz.getSimpleName().endsWith("Tests")
                            && !clazz.getSimpleName().endsWith("IT")
                            && !clazz.getPackageName().contains(".fixtures")
                            && !clazz.getPackageName().contains(".testsupport"));

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(EasyAuditApplication.class, PRODUCTION_CLASSES_ONLY).verify();
    }
}

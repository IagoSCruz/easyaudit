package br.com.easyaudit;

import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private static final ImportOption NO_TESTS = location ->
            ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
                    && !location.contains("-tests.jar");

    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(EasyAuditApplication.class, NO_TESTS).verify();
    }
}

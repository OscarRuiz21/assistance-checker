package com.assistance;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifica que la estructura de módulos respete las dependencias declaradas en
 * cada package-info.java. Falla el build si:
 *  - Un módulo importa otro no listado en allowedDependencies.
 *  - Un módulo accede a tipos del subpaquete `internal/` de otro módulo.
 *  - Hay ciclos de dependencias entre módulos.
 *
 * También genera diagramas PlantUML y AsciiDoc en target/spring-modulith-docs/.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentationSnapshots() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }

    @Test
    void printsModuleSummary() {
        modules.forEach(m -> System.out.println(
                m.getIdentifier() + " → " + m.getDirectDependencies(modules)));
    }
}

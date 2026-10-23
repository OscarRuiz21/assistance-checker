package com.assistance.attendance.internal.service;

import com.assistance.shared.api.TenantScope;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitario (sin Docker/BD real): el contrato de filtrado por planta
 * (TenantScope.plantIds/canAccessPlant) del que dependen
 * AttendanceServiceImpl/EmployeeServiceImpl/EventStreamHub para su defensa
 * en profundidad "además de RLS" (Fase 2.3/2.5). plantIds() vacío = sin
 * restricción (tenant_admin/super_admin); no vacío = solo esas plantas
 * (supervisor/kiosko).
 */
class PlantScopeFilteringTest {

    @Test
    void plantIdsVacio_puedeAccederACualquierPlanta() {
        TenantScope scope = new TenantScope(UUID.randomUUID(), UUID.randomUUID(), null, Set.of(), Set.of("tenant_admin"));

        assertThat(scope.canAccessPlant(UUID.randomUUID())).isTrue();
        assertThat(scope.canAccessPlant(UUID.randomUUID())).isTrue();
    }

    @Test
    void plantIdsRestringido_soloAccedeALasPlantasListadas() {
        UUID allowedPlant = UUID.randomUUID();
        UUID otherPlant = UUID.randomUUID();
        TenantScope scope = new TenantScope(
                UUID.randomUUID(), UUID.randomUUID(), null, Set.of(allowedPlant), Set.of("supervisor"));

        assertThat(scope.canAccessPlant(allowedPlant)).isTrue();
        assertThat(scope.canAccessPlant(otherPlant)).isFalse();
    }

    @Test
    void kioskoScope_tieneExactamenteUnaPlanta() {
        UUID plantId = UUID.randomUUID();
        TenantScope kioskoScope = TenantScope.system(UUID.randomUUID());
        TenantScope kioskoWithPlant = new TenantScope(
                kioskoScope.tenantId(), null, UUID.randomUUID(), Set.of(plantId), Set.of());

        assertThat(kioskoWithPlant.plantIds()).containsExactly(plantId);
        assertThat(kioskoWithPlant.userId()).isNull();
        assertThat(kioskoWithPlant.kioskoId()).isNotNull();
    }

    @Test
    void systemScope_sinRestriccionDePlanta_usadoPorJobsYSeeds() {
        TenantScope system = TenantScope.system(UUID.randomUUID());

        assertThat(system.plantIds()).isEmpty();
        assertThat(system.hasRole("system")).isTrue();
        assertThat(system.canAccessPlant(UUID.randomUUID())).isTrue();
    }
}

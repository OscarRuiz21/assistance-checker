package com.assistance.attendance.internal.repo;

import com.assistance.attendance.internal.domain.EventRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface EventRawRepository extends JpaRepository<EventRaw, UUID> {

    /**
     * {@code plantIds} null significa "todas las plantas del tenant"
     * (tenant_admin/super_admin); no-null y no vacío restringe a esas
     * plantas (defensa en profundidad adicional a RLS, requisito Fase 2.3).
     * Nunca se invoca con un Set vacío (la capa de servicio normaliza
     * "vacío" a null antes de llamar).
     */
    @Query("SELECT e FROM EventRaw e WHERE e.tenantId = :tenantId "
            + "AND (:plantIds IS NULL OR e.plantId IN :plantIds) "
            + "AND e.eventTime BETWEEN :from AND :to ORDER BY e.eventTime DESC")
    Page<EventRaw> findAllInScope(@Param("tenantId") UUID tenantId,
                                  @Param("plantIds") Set<UUID> plantIds,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);

    @Query("SELECT e FROM EventRaw e WHERE e.tenantId = :tenantId AND e.employeeId = :employeeId "
            + "AND (:plantIds IS NULL OR e.plantId IN :plantIds) "
            + "AND e.eventTime BETWEEN :from AND :to ORDER BY e.eventTime DESC")
    Page<EventRaw> findAllInScopeForEmployee(@Param("tenantId") UUID tenantId,
                                             @Param("employeeId") UUID employeeId,
                                             @Param("plantIds") Set<UUID> plantIds,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             Pageable pageable);

    @Query("SELECT COUNT(e) FROM EventRaw e WHERE e.tenantId = :tenantId "
            + "AND (:plantIds IS NULL OR e.plantId IN :plantIds) AND e.eventTime BETWEEN :from AND :to")
    long countInRange(@Param("tenantId") UUID tenantId, @Param("plantIds") Set<UUID> plantIds,
                      @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(DISTINCT e.employeeId) FROM EventRaw e WHERE e.tenantId = :tenantId "
            + "AND (:plantIds IS NULL OR e.plantId IN :plantIds) AND e.eventTime BETWEEN :from AND :to")
    long countDistinctEmployees(@Param("tenantId") UUID tenantId, @Param("plantIds") Set<UUID> plantIds,
                                @Param("from") Instant from, @Param("to") Instant to);
}

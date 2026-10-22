package com.assistance.attendance.internal.repo;

import com.assistance.attendance.internal.domain.EventRaw;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface EventRawRepository extends JpaRepository<EventRaw, UUID> {

    Page<EventRaw> findAllByTenantIdAndEventTimeBetweenOrderByEventTimeDesc(
            UUID tenantId, Instant from, Instant to, Pageable pageable);

    Page<EventRaw> findAllByTenantIdAndEmployeeIdAndEventTimeBetweenOrderByEventTimeDesc(
            UUID tenantId, UUID employeeId, Instant from, Instant to, Pageable pageable);

    @Query("SELECT COUNT(e) FROM EventRaw e WHERE e.tenantId = :tenantId AND e.eventTime BETWEEN :from AND :to")
    long countInRange(@Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(DISTINCT e.employeeId) FROM EventRaw e WHERE e.tenantId = :tenantId AND e.eventTime BETWEEN :from AND :to")
    long countDistinctEmployees(@Param("tenantId") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to);
}

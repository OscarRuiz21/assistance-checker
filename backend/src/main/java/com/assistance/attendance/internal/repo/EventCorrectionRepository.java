package com.assistance.attendance.internal.repo;

import com.assistance.attendance.internal.domain.EventCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventCorrectionRepository extends JpaRepository<EventCorrection, UUID> {

    List<EventCorrection> findAllByTenantIdAndEventRawId(UUID tenantId, UUID eventRawId);
}

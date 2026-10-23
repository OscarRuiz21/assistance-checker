package com.assistance.attendance.internal.repo;

import com.assistance.attendance.internal.domain.ReviewQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewQueueRepository extends JpaRepository<ReviewQueueEntry, UUID> {

    List<ReviewQueueEntry> findAllByTenantIdAndStatusOrderByDetectedAtDesc(UUID tenantId, String status);
}

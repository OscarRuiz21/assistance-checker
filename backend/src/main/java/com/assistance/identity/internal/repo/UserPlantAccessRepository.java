package com.assistance.identity.internal.repo;

import com.assistance.identity.internal.domain.UserPlantAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserPlantAccessRepository extends JpaRepository<UserPlantAccess, UUID> {

    List<UserPlantAccess> findAllByUserId(UUID userId);
}

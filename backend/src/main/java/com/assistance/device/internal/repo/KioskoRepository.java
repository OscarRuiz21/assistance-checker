package com.assistance.device.internal.repo;

import com.assistance.device.internal.domain.Kiosko;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KioskoRepository extends JpaRepository<Kiosko, UUID> {}

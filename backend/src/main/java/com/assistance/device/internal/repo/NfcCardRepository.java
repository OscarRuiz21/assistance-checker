package com.assistance.device.internal.repo;

import com.assistance.device.internal.domain.NfcCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NfcCardRepository extends JpaRepository<NfcCard, UUID> {

    Optional<NfcCard> findByUid(String uid);
}

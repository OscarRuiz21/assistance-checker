package com.assistance.device.internal.service;

import com.assistance.device.api.DeviceApi;
import com.assistance.device.internal.domain.NfcCard;
import com.assistance.device.internal.repo.KioskoRepository;
import com.assistance.device.internal.repo.NfcCardRepository;
import com.assistance.device.internal.service.mapper.DeviceMapper;
import com.assistance.device.api.KioskoView;
import com.assistance.device.api.NfcCardView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DeviceServiceImpl implements DeviceApi {

    private final NfcCardRepository cardRepo;
    private final KioskoRepository kioskoRepo;
    private final DeviceMapper mapper;

    public DeviceServiceImpl(NfcCardRepository cardRepo,
                             KioskoRepository kioskoRepo,
                             DeviceMapper mapper) {
        this.cardRepo = cardRepo;
        this.kioskoRepo = kioskoRepo;
        this.mapper = mapper;
    }

    @Override
    public Optional<NfcCardView> findCardByUid(String uid) {
        return cardRepo.findByUid(uid).map(mapper::toView);
    }

    @Override
    public Optional<UUID> resolveActiveEmployeeIdByCardUid(String uid) {
        return cardRepo.findByUid(uid)
                .filter(c -> "active".equals(c.getStatus()))
                .map(NfcCard::getEmployeeId);
    }

    @Override
    public Optional<KioskoView> findKiosko(UUID kioskoId) {
        return kioskoRepo.findById(kioskoId).map(mapper::toView);
    }
}

package com.assistance.device.internal.service;

import com.assistance.device.api.DeviceApi;
import com.assistance.device.internal.domain.NfcCard;
import com.assistance.device.internal.repo.KioskoRepository;
import com.assistance.device.internal.repo.NfcCardRepository;
import com.assistance.device.internal.service.mapper.DeviceMapper;
import com.assistance.device.api.KioskoView;
import com.assistance.device.api.NfcCardView;
import com.assistance.shared.api.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Todas las consultas resuelven el tenant desde {@link TenantContext#require()}
 * en vez de recibirlo como parámetro: la firma pública de {@link DeviceApi}
 * no cambia (la consumen AttendanceServiceImpl y EmployeeController de
 * AGENTE A), pero internamente ya no hay forma de consultar sin tenant.
 * RLS (V3__tenant_rls.sql) es la segunda capa de defensa si este filtro se
 * olvidara en algún método nuevo.
 */
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
        UUID tenantId = TenantContext.require().tenantId();
        return cardRepo.findByTenantIdAndUid(tenantId, uid).map(mapper::toView);
    }

    @Override
    public Optional<UUID> resolveActiveEmployeeIdByCardUid(String uid) {
        UUID tenantId = TenantContext.require().tenantId();
        return cardRepo.findByTenantIdAndUid(tenantId, uid)
                .filter(c -> "active".equals(c.getStatus()))
                .map(NfcCard::getEmployeeId);
    }

    @Override
    public Optional<KioskoView> findKiosko(UUID kioskoId) {
        UUID tenantId = TenantContext.require().tenantId();
        return kioskoRepo.findByIdAndTenantId(kioskoId, tenantId).map(mapper::toView);
    }
}

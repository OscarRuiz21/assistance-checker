package com.assistance.employee.internal.service;

import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.internal.domain.Employee;
import com.assistance.employee.internal.repo.EmployeeRepository;
import com.assistance.employee.internal.service.mapper.EmployeeMapper;
import com.assistance.employee.api.EmployeeView;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resuelve tenant (y, al listar, plantas permitidas) desde
 * {@link TenantContext#require()}. La firma pública de {@link EmployeeApi}
 * no cambia; el filtrado es interno (Fase 2.3, defensa en profundidad además
 * de RLS).
 */
@Service
@Transactional(readOnly = true)
public class EmployeeServiceImpl implements EmployeeApi {

    private final EmployeeRepository repo;
    private final EmployeeMapper mapper;
    private final PinVerifier pinVerifier;

    public EmployeeServiceImpl(EmployeeRepository repo,
                               EmployeeMapper mapper,
                               PinVerifier pinVerifier) {
        this.repo = repo;
        this.mapper = mapper;
        this.pinVerifier = pinVerifier;
    }

    @Override
    public Optional<EmployeeView> findActive(UUID employeeId) {
        UUID tenantId = TenantContext.require().tenantId();
        return repo.findByIdAndTenantIdAndStatus(employeeId, tenantId, "active")
                .map(mapper::toView);
    }

    @Override
    public Optional<EmployeeView> findActiveByEmployeeNumber(String employeeNumber) {
        UUID tenantId = TenantContext.require().tenantId();
        return repo.findByTenantIdAndEmployeeNumberAndStatus(tenantId, employeeNumber, "active")
                .map(mapper::toView);
    }

    @Override
    public List<EmployeeView> listActive() {
        TenantScope scope = TenantContext.require();
        return repo.findAllByTenantIdAndStatusOrderByFullName(scope.tenantId(), "active").stream()
                .filter(e -> scope.plantIds().isEmpty() || isInScope(e, scope))
                .map(mapper::toView)
                .toList();
    }

    /** scope.plantIds() no vacío = admin restringido a ciertas plantas (Fase 2.3). */
    private boolean isInScope(Employee e, TenantScope scope) {
        return e.getDefaultPlantId() != null && scope.canAccessPlant(e.getDefaultPlantId());
    }

    @Override
    public boolean verifyPin(UUID employeeId, String pin) {
        return pinVerifier.verify(employeeId, pin);
    }
}

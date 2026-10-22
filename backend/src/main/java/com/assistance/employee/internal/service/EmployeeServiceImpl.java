package com.assistance.employee.internal.service;

import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.internal.repo.EmployeeRepository;
import com.assistance.employee.internal.service.mapper.EmployeeMapper;
import com.assistance.employee.api.EmployeeView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        return repo.findById(employeeId)
                .filter(e -> "active".equals(e.getStatus()))
                .map(mapper::toView);
    }

    @Override
    public Optional<EmployeeView> findActiveByEmployeeNumber(String employeeNumber) {
        return repo.findByEmployeeNumberAndStatus(employeeNumber, "active").map(mapper::toView);
    }

    @Override
    public List<EmployeeView> listActive() {
        return repo.findAllByStatusOrderByFullName("active").stream()
                .map(mapper::toView)
                .toList();
    }

    @Override
    public boolean verifyPin(UUID employeeId, String pin) {
        return pinVerifier.verify(employeeId, pin);
    }
}

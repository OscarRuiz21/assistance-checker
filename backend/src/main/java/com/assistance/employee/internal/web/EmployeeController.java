package com.assistance.employee.internal.web;

import com.assistance.device.api.DeviceApi;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.internal.service.exception.CardNotFoundException;
import com.assistance.employee.internal.service.exception.EmployeeNotFoundException;
import com.assistance.employee.api.EmployeeView;
import com.assistance.shared.api.ApiException;
import com.assistance.shared.api.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    // Rate limit de lookup by-card: 120/min por UID consultado (generoso para
    // reintentos normales de un kiosko, pero corta un escaneo masivo de UIDs).
    private static final int MAX_LOOKUPS_PER_MINUTE = 120;
    private final RateLimiter.Bucket byCardLookups = RateLimiter.windowOf(Duration.ofMinutes(1));

    private final EmployeeApi employees;
    private final DeviceApi devices;

    public EmployeeController(EmployeeApi employees, DeviceApi devices) {
        this.employees = employees;
        this.devices = devices;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('super_admin','tenant_admin','rh','supervisor')")
    public List<EmployeeView> list() {
        return employees.listActive();
    }

    @GetMapping("/by-card/{uid}")
    public EmployeeView byCard(@PathVariable String uid) {
        if (byCardLookups.increment(uid) > MAX_LOOKUPS_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Demasiadas consultas para esta tarjeta, intente de nuevo en un momento");
        }
        UUID empId = devices.resolveActiveEmployeeIdByCardUid(uid)
                .orElseThrow(CardNotFoundException::new);
        return employees.findActive(empId)
                .orElseThrow(EmployeeNotFoundException::new);
    }
}

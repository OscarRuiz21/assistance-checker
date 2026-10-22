package com.assistance.employee.internal.web;

import com.assistance.device.api.DeviceApi;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.internal.service.exception.CardNotFoundException;
import com.assistance.employee.internal.service.exception.EmployeeNotFoundException;
import com.assistance.employee.api.EmployeeView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeApi employees;
    private final DeviceApi devices;

    public EmployeeController(EmployeeApi employees, DeviceApi devices) {
        this.employees = employees;
        this.devices = devices;
    }

    @GetMapping
    public List<EmployeeView> list() {
        return employees.listActive();
    }

    @GetMapping("/by-card/{uid}")
    public EmployeeView byCard(@PathVariable String uid) {
        UUID empId = devices.resolveActiveEmployeeIdByCardUid(uid)
                .orElseThrow(CardNotFoundException::new);
        return employees.findActive(empId)
                .orElseThrow(EmployeeNotFoundException::new);
    }
}

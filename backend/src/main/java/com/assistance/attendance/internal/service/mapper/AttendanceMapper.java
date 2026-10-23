package com.assistance.attendance.internal.service.mapper;

import com.assistance.attendance.api.RecordEventCommand;
import com.assistance.attendance.event.AttendanceRecorded;
import com.assistance.attendance.internal.domain.EventRaw;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.EventType;
import com.assistance.attendance.api.EventView;
import com.assistance.employee.api.EmployeeView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AttendanceMapper {

    public EventRaw toEntity(RecordEventCommand cmd,
                             UUID id,
                             UUID tenantId,
                             UUID plantId,
                             UUID kioskoId,
                             UUID employeeId,
                             UUID nfcCardId,
                             UUID recordedByUserId) {
        EventRaw e = new EventRaw();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setPlantId(plantId);
        e.setKioskoId(kioskoId);
        e.setEmployeeId(employeeId);
        e.setEventType(cmd.eventType().dbValue());
        e.setCaptureMethod(cmd.captureMethod().dbValue());
        e.setDeviceTime(cmd.deviceTime());
        e.setEventTime(cmd.deviceTime());
        e.setReceivedAt(Instant.now());
        e.setNfcCardId(nfcCardId);
        e.setValid(true);
        e.setRecordedByUserId(recordedByUserId);
        return e;
    }

    public EventView toView(EventRaw e, EmployeeView employee) {
        return new EventView(
                e.getId(),
                e.getEmployeeId(),
                employee != null ? employee.fullName() : "?",
                employee != null ? employee.employeeNumber() : "?",
                EventType.fromDb(e.getEventType()),
                CaptureMethod.fromDb(e.getCaptureMethod()),
                e.getEventTime(),
                e.getReceivedAt(),
                e.isValid());
    }

    public AttendanceRecorded toEvent(EventRaw saved, EventType type, CaptureMethod method) {
        return new AttendanceRecorded(
                saved.getId(),
                saved.getTenantId(),
                saved.getPlantId(),
                saved.getEmployeeId(),
                type,
                method,
                saved.getEventTime());
    }
}

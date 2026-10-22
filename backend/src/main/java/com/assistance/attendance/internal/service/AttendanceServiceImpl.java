package com.assistance.attendance.internal.service;

import com.assistance.attendance.api.AttendanceApi;
import com.assistance.attendance.api.DailyBreakdown;
import com.assistance.attendance.api.EmployeeWorkSummary;
import com.assistance.attendance.api.RecordEventCommand;
import com.assistance.attendance.internal.domain.EventRaw;
import com.assistance.attendance.internal.repo.EventRawRepository;
import com.assistance.attendance.internal.service.exception.CardBlockedException;
import com.assistance.attendance.internal.service.exception.CardLostException;
import com.assistance.attendance.internal.service.exception.CardNotAssignedException;
import com.assistance.attendance.internal.service.exception.CardNotFoundException;
import com.assistance.attendance.internal.service.exception.EmployeeInactiveException;
import com.assistance.attendance.internal.service.exception.EmployeeNotFoundException;
import com.assistance.attendance.internal.service.exception.InvalidPinException;
import com.assistance.attendance.internal.service.exception.MissingIdentifierException;
import com.assistance.attendance.internal.service.exception.PinRequiredException;
import com.assistance.attendance.internal.service.mapper.AttendanceMapper;
import com.assistance.attendance.api.CaptureMethod;
import com.assistance.attendance.api.DashboardKpis;
import com.assistance.attendance.api.EventView;
import com.assistance.device.api.DeviceApi;
import com.assistance.device.api.CardStatus;
import com.assistance.device.api.NfcCardView;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.api.EmployeeView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AttendanceServiceImpl implements AttendanceApi {

    private static final UUID DEMO_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_PLANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMO_KIOSKO_ID = UUID.fromString("eeee0001-0000-0000-0000-000000000001");

    private final EventRawRepository repo;
    private final EmployeeApi employees;
    private final DeviceApi devices;
    private final ApplicationEventPublisher events;
    private final AttendanceMapper mapper;
    private final JdbcTemplate jdbc;

    public AttendanceServiceImpl(EventRawRepository repo,
                                 EmployeeApi employees,
                                 DeviceApi devices,
                                 ApplicationEventPublisher events,
                                 AttendanceMapper mapper,
                                 JdbcTemplate jdbc) {
        this.repo = repo;
        this.employees = employees;
        this.devices = devices;
        this.events = events;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public EventView record(RecordEventCommand cmd) {
        Optional<NfcCardView> card = lookupCard(cmd);
        EmployeeView employee = resolveEmployee(cmd, card.orElse(null));
        verifyPinIfNeeded(cmd, employee);

        EventRaw entity = mapper.toEntity(
                cmd,
                UUID.randomUUID(),
                DEMO_TENANT_ID,
                DEMO_PLANT_ID,
                cmd.kioskoId() != null ? cmd.kioskoId() : DEMO_KIOSKO_ID,
                employee.id(),
                card.map(NfcCardView::id).orElse(null));

        EventRaw saved = repo.save(entity);
        events.publishEvent(mapper.toEvent(saved, cmd.eventType(), cmd.captureMethod()));

        return mapper.toView(saved, employee);
    }

    private Optional<NfcCardView> lookupCard(RecordEventCommand cmd) {
        if (cmd.cardUid() == null) return Optional.empty();
        NfcCardView card = devices.findCardByUid(cmd.cardUid())
                .orElseThrow(CardNotFoundException::new);
        if (card.status() == CardStatus.BLOCKED) throw new CardBlockedException(card.blockedReason());
        if (card.status() == CardStatus.LOST) throw new CardLostException();
        return Optional.of(card);
    }

    private EmployeeView resolveEmployee(RecordEventCommand cmd, NfcCardView card) {
        if (cmd.employeeId() != null) {
            return employees.findActive(cmd.employeeId())
                    .orElseThrow(() -> new EmployeeNotFoundException("por id"));
        }
        if (card != null) {
            if (card.employeeId() == null) throw new CardNotAssignedException();
            return employees.findActive(card.employeeId())
                    .orElseThrow(EmployeeInactiveException::new);
        }
        if (cmd.employeeNumber() != null) {
            return employees.findActiveByEmployeeNumber(cmd.employeeNumber())
                    .orElseThrow(() -> new EmployeeNotFoundException("por número " + cmd.employeeNumber()));
        }
        throw new MissingIdentifierException();
    }

    private void verifyPinIfNeeded(RecordEventCommand cmd, EmployeeView employee) {
        if (cmd.captureMethod() != CaptureMethod.PIN) return;
        if (cmd.pin() == null || cmd.pin().isBlank()) throw new PinRequiredException();
        if (!employees.verifyPin(employee.id(), cmd.pin())) throw new InvalidPinException();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventView> list(UUID employeeId, Instant from, Instant to, int limit) {
        var pageable = PageRequest.of(0, Math.min(limit, 5000));
        var page = employeeId != null
                ? repo.findAllByTenantIdAndEmployeeIdAndEventTimeBetweenOrderByEventTimeDesc(
                        DEMO_TENANT_ID, employeeId, from, to, pageable)
                : repo.findAllByTenantIdAndEventTimeBetweenOrderByEventTimeDesc(
                        DEMO_TENANT_ID, from, to, pageable);
        var ids = page.getContent().stream().map(EventRaw::getEmployeeId).distinct().toList();
        var employeeMap = ids.stream()
                .map(employees::findActive)
                .filter(Optional::isPresent).map(Optional::get)
                .collect(java.util.stream.Collectors.toMap(EmployeeView::id, ev -> ev));
        return page.getContent().stream()
                .map(e -> mapper.toView(e, employeeMap.get(e.getEmployeeId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeWorkSummary> summarize(Instant from, Instant to) {
        // 1) SQL agrega por empleado-día: primer ci, primer lo, primer li, último co, count
        // 2) Sumar a través de los días por empleado
        String sql = """
                WITH daily AS (
                    SELECT
                        employee_id,
                        DATE(event_time AT TIME ZONE 'America/Mexico_City') AS biz_date,
                        MIN(CASE WHEN event_type='check_in'  THEN event_time END) AS ci,
                        MIN(CASE WHEN event_type='lunch_out' THEN event_time END) AS lo,
                        MIN(CASE WHEN event_type='lunch_in'  THEN event_time END) AS li,
                        MAX(CASE WHEN event_type='check_out' THEN event_time END) AS co,
                        COUNT(*) AS evts
                    FROM event_raw
                    WHERE tenant_id = ?::uuid
                      AND event_time BETWEEN ? AND ?
                    GROUP BY employee_id, DATE(event_time AT TIME ZONE 'America/Mexico_City')
                )
                SELECT
                    employee_id,
                    COUNT(*) FILTER (WHERE ci IS NOT NULL)                                          AS days_worked,
                    COALESCE(SUM(evts), 0)::bigint                                                  AS total_events,
                    COALESCE(SUM(EXTRACT(EPOCH FROM (co - ci)))::bigint, 0)                         AS office_secs,
                    COALESCE(SUM(EXTRACT(EPOCH FROM (li - lo)))::bigint, 0)                         AS lunch_secs,
                    MAX(ci)                                                                          AS last_ci
                FROM daily
                GROUP BY employee_id
                """;

        Map<UUID, EmployeeWorkSummary> agg = new HashMap<>();
        jdbc.query(sql, rs -> {
            UUID empId = UUID.fromString(rs.getString("employee_id"));
            int days = rs.getInt("days_worked");
            int evts = rs.getInt("total_events");
            long office = rs.getLong("office_secs");
            long lunch = rs.getLong("lunch_secs");
            long worked = Math.max(0, office - lunch);
            Timestamp lastCi = rs.getTimestamp("last_ci");
            agg.put(empId, new EmployeeWorkSummary(
                    empId, "", "",
                    days, evts, office, lunch, worked,
                    lastCi != null ? lastCi.toInstant() : null));
        }, DEMO_TENANT_ID.toString(), Timestamp.from(from), Timestamp.from(to));

        // Combinar con TODOS los empleados activos (los que no tienen eventos aparecen en cero)
        return employees.listActive().stream()
                .map(e -> {
                    EmployeeWorkSummary base = agg.get(e.id());
                    if (base == null) {
                        return new EmployeeWorkSummary(e.id(), e.fullName(), e.employeeNumber(),
                                0, 0, 0, 0, 0, null);
                    }
                    return new EmployeeWorkSummary(
                            e.id(), e.fullName(), e.employeeNumber(),
                            base.daysWorked(), base.totalEvents(),
                            base.secondsInOffice(), base.secondsLunch(), base.secondsWorked(),
                            base.lastCheckIn());
                })
                // ordenar por más activo (más días, luego más horas)
                .sorted((a, b) -> {
                    int byDays = Integer.compare(b.daysWorked(), a.daysWorked());
                    if (byDays != 0) return byDays;
                    return Long.compare(b.secondsWorked(), a.secondsWorked());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyBreakdown> dailyBreakdown(UUID employeeId, Instant from, Instant to) {
        String sql = """
                SELECT
                    DATE(event_time AT TIME ZONE 'America/Mexico_City') AS biz_date,
                    MIN(CASE WHEN event_type='check_in'  THEN event_time END) AS ci,
                    MIN(CASE WHEN event_type='lunch_out' THEN event_time END) AS lo,
                    MIN(CASE WHEN event_type='lunch_in'  THEN event_time END) AS li,
                    MAX(CASE WHEN event_type='check_out' THEN event_time END) AS co
                FROM event_raw
                WHERE tenant_id = ?::uuid
                  AND employee_id = ?::uuid
                  AND event_time BETWEEN ? AND ?
                GROUP BY DATE(event_time AT TIME ZONE 'America/Mexico_City')
                ORDER BY biz_date DESC
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            LocalDate date = rs.getDate("biz_date").toLocalDate();
            Timestamp ciTs = rs.getTimestamp("ci");
            Timestamp loTs = rs.getTimestamp("lo");
            Timestamp liTs = rs.getTimestamp("li");
            Timestamp coTs = rs.getTimestamp("co");
            Instant ci = ciTs != null ? ciTs.toInstant() : null;
            Instant lo = loTs != null ? loTs.toInstant() : null;
            Instant li = liTs != null ? liTs.toInstant() : null;
            Instant co = coTs != null ? coTs.toInstant() : null;

            long office = (ci != null && co != null) ? (co.getEpochSecond() - ci.getEpochSecond()) : 0L;
            long lunch = (lo != null && li != null) ? (li.getEpochSecond() - lo.getEpochSecond()) : 0L;
            long worked = Math.max(0, office - lunch);
            boolean complete = ci != null && co != null;

            return new DailyBreakdown(date, ci, lo, li, co, office, lunch, worked, complete);
        }, DEMO_TENANT_ID.toString(), employeeId.toString(),
                Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardKpis dashboard(Instant from, Instant to) {
        long total = repo.countInRange(DEMO_TENANT_ID, from, to);
        long unique = repo.countDistinctEmployees(DEMO_TENANT_ID, from, to);
        Long late = jdbc.queryForObject("""
                SELECT COUNT(*) FROM event_raw
                WHERE tenant_id = ?::uuid
                  AND event_type = 'check_in'
                  AND event_time BETWEEN ? AND ?
                  AND EXTRACT(HOUR FROM event_time AT TIME ZONE 'America/Mexico_City') >= 8
                """, Long.class, DEMO_TENANT_ID.toString(),
                Timestamp.from(from), Timestamp.from(to));
        return new DashboardKpis(total, unique, late == null ? 0 : late, from, to);
    }
}

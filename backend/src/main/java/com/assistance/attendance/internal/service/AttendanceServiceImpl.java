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
import com.assistance.compliance.api.AuditApi;
import com.assistance.device.api.DeviceApi;
import com.assistance.device.api.CardStatus;
import com.assistance.device.api.NfcCardView;
import com.assistance.employee.api.EmployeeApi;
import com.assistance.employee.api.EmployeeView;
import com.assistance.shared.api.TenantContext;
import com.assistance.shared.api.TenantScope;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AttendanceServiceImpl implements AttendanceApi {

    private final EventRawRepository repo;
    private final EmployeeApi employees;
    private final DeviceApi devices;
    private final ApplicationEventPublisher events;
    private final AttendanceMapper mapper;
    private final JdbcTemplate jdbc;
    private final TimezoneResolver timezoneResolver;
    private final AuditApi auditApi;

    public AttendanceServiceImpl(EventRawRepository repo,
                                 EmployeeApi employees,
                                 DeviceApi devices,
                                 ApplicationEventPublisher events,
                                 AttendanceMapper mapper,
                                 JdbcTemplate jdbc,
                                 TimezoneResolver timezoneResolver,
                                 AuditApi auditApi) {
        this.repo = repo;
        this.employees = employees;
        this.devices = devices;
        this.events = events;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.timezoneResolver = timezoneResolver;
        this.auditApi = auditApi;
    }

    @Override
    @Transactional
    public EventView record(RecordEventCommand cmd) {
        TenantScope scope = TenantContext.require();
        Optional<NfcCardView> card = lookupCard(cmd);
        EmployeeView employee = resolveEmployee(cmd, card.orElse(null));
        verifyPinIfNeeded(cmd, employee);

        UUID plantId = resolvePlantId(scope, employee);
        UUID kioskoId = cmd.kioskoId() != null ? cmd.kioskoId() : scope.kioskoId();
        // El registro manual (hecho por un admin/RH) lleva recordedByUserId;
        // la captura normal en kiosko (nfc/pin) no.
        UUID recordedByUserId = cmd.captureMethod() == CaptureMethod.MANUAL ? scope.userId() : null;

        EventRaw entity = mapper.toEntity(
                cmd,
                UUID.randomUUID(),
                scope.tenantId(),
                plantId,
                kioskoId,
                employee.id(),
                card.map(NfcCardView::id).orElse(null),
                recordedByUserId);

        EventRaw saved = repo.save(entity);
        events.publishEvent(mapper.toEvent(saved, cmd.eventType(), cmd.captureMethod()));

        return mapper.toView(saved, employee);
    }

    /**
     * Resuelve la planta del evento: la del kiosko si vino de un kiosko
     * (scope.kioskoId() != null, scope.plantIds() tiene exactamente la
     * planta de ese kiosko por diseño del filtro de autenticación), o la
     * planta por defecto del empleado para altas manuales de un admin.
     */
    private UUID resolvePlantId(TenantScope scope, EmployeeView employee) {
        if (scope.kioskoId() != null && scope.plantIds().size() == 1) {
            return scope.plantIds().iterator().next();
        }
        if (employee.defaultPlantId() != null) {
            return employee.defaultPlantId();
        }
        throw new IllegalStateException(
                "No se pudo resolver la planta del evento: ni el scope ni el empleado la definen");
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
        return listInternal(employeeId, from, to, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventView> listForExport(UUID employeeId, Instant from, Instant to, int limit) {
        List<EventView> rows = listInternal(employeeId, from, to, limit);
        auditApi.record("event.export_csv", "event_raw", employeeId, Map.of(
                "from", from.toString(),
                "to", to.toString(),
                "rowCount", rows.size()));
        return rows;
    }

    private List<EventView> listInternal(UUID employeeId, Instant from, Instant to, int limit) {
        TenantScope scope = TenantContext.require();
        Set<UUID> plantFilter = scopedPlantIds(scope);
        var pageable = PageRequest.of(0, Math.min(limit, 5000));
        var page = employeeId != null
                ? repo.findAllInScopeForEmployee(scope.tenantId(), employeeId, plantFilter, from, to, pageable)
                : repo.findAllInScope(scope.tenantId(), plantFilter, from, to, pageable);
        var ids = page.getContent().stream().map(EventRaw::getEmployeeId).distinct().toList();
        var employeeMap = ids.stream()
                .map(employees::findActive)
                .filter(Optional::isPresent).map(Optional::get)
                .collect(Collectors.toMap(EmployeeView::id, ev -> ev));
        return page.getContent().stream()
                .map(e -> mapper.toView(e, employeeMap.get(e.getEmployeeId())))
                .toList();
    }

    /** null = sin restricción de planta (tenant_admin/super_admin); JPQL lo interpreta como "todas". */
    private Set<UUID> scopedPlantIds(TenantScope scope) {
        return scope.plantIds().isEmpty() ? null : scope.plantIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeWorkSummary> summarize(Instant from, Instant to) {
        TenantScope scope = TenantContext.require();
        String tz = timezoneResolver.resolve(scope);
        String plantFilterSql = plantFilterSql(scope, "plant_id");

        // 1) SQL agrega por empleado-día: primer ci, primer lo, primer li, último co, count
        // 2) Sumar a través de los días por empleado
        String sql = """
                WITH daily AS (
                    SELECT
                        employee_id,
                        DATE(event_time AT TIME ZONE ?) AS biz_date,
                        MIN(CASE WHEN event_type='check_in'  THEN event_time END) AS ci,
                        MIN(CASE WHEN event_type='lunch_out' THEN event_time END) AS lo,
                        MIN(CASE WHEN event_type='lunch_in'  THEN event_time END) AS li,
                        MAX(CASE WHEN event_type='check_out' THEN event_time END) AS co,
                        COUNT(*) AS evts
                    FROM event_raw
                    WHERE tenant_id = ?::uuid
                      AND event_time BETWEEN ? AND ?
                      """ + plantFilterSql + """

                    GROUP BY employee_id, DATE(event_time AT TIME ZONE ?)
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
        }, tz, scope.tenantId().toString(), Timestamp.from(from), Timestamp.from(to), tz);

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
        TenantScope scope = TenantContext.require();
        String tz = timezoneResolver.resolve(scope);
        String plantFilterSql = plantFilterSql(scope, "plant_id");

        String sql = """
                SELECT
                    DATE(event_time AT TIME ZONE ?) AS biz_date,
                    MIN(CASE WHEN event_type='check_in'  THEN event_time END) AS ci,
                    MIN(CASE WHEN event_type='lunch_out' THEN event_time END) AS lo,
                    MIN(CASE WHEN event_type='lunch_in'  THEN event_time END) AS li,
                    MAX(CASE WHEN event_type='check_out' THEN event_time END) AS co
                FROM event_raw
                WHERE tenant_id = ?::uuid
                  AND employee_id = ?::uuid
                  AND event_time BETWEEN ? AND ?
                  """ + plantFilterSql + """

                GROUP BY DATE(event_time AT TIME ZONE ?)
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
        }, tz, scope.tenantId().toString(), employeeId.toString(),
                Timestamp.from(from), Timestamp.from(to), tz);
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardKpis dashboard(Instant from, Instant to) {
        TenantScope scope = TenantContext.require();
        String tz = timezoneResolver.resolve(scope);
        Set<UUID> plantFilter = scopedPlantIds(scope);
        String plantFilterSql = plantFilterSql(scope, "plant_id");

        long total = repo.countInRange(scope.tenantId(), plantFilter, from, to);
        long unique = repo.countDistinctEmployees(scope.tenantId(), plantFilter, from, to);
        String lateSql = """
                SELECT COUNT(*) FROM event_raw
                WHERE tenant_id = ?::uuid
                  AND event_type = 'check_in'
                  AND event_time BETWEEN ? AND ?
                  AND EXTRACT(HOUR FROM event_time AT TIME ZONE ?) >= 8
                  """ + plantFilterSql;
        Long late = jdbc.queryForObject(lateSql, Long.class, scope.tenantId().toString(),
                Timestamp.from(from), Timestamp.from(to), tz);
        return new DashboardKpis(total, unique, late == null ? 0 : late, from, to);
    }

    /**
     * Fragmento SQL (con placeholder posicional al final de WHERE) que
     * restringe por planta cuando el scope no tiene acceso a todas. Los
     * UUIDs se embeben directamente (no vía bind param) porque
     * {@link UUID#toString()} nunca puede producir una comilla o `;` —
     * formato fijo hex-con-guiones — así que no hay riesgo de inyección, y
     * evita la complejidad de pasar arrays a JdbcTemplate.
     */
    private String plantFilterSql(TenantScope scope, String column) {
        if (scope.plantIds().isEmpty()) {
            return "";
        }
        String inList = scope.plantIds().stream()
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(","));
        return " AND " + column + " IN (" + inList + ") ";
    }
}

# Patrones arquitectónicos del proyecto

Catálogo de los patrones aplicados en el código, con justificación, código de ejemplo y verificación. Cumple con SOLID, Clean Code y diseño sostenible para un equipo Java/Spring Boot enterprise.

Formaliza las decisiones en [ADR-006](./adrs/ADR-006-arquitectura-aplicacion.md).

## Estructura de capas

```
HTTP layer (controllers, advice, filters)
        ↓
AOP cross-cutting (aspects, custom annotations)
        ↓
Application layer (services como interfaz + Impl)
        ↓
Domain (entities, value objects, ports, domain events)
        ↓
Infrastructure (repos JPA, external clients, mappers)
```

## 1. Hexagonal architecture / Ports & Adapters

El **dominio** define **puertos** (interfaces); los **adapters** los satisfacen. El dominio no sabe nada de HTTP, JPA, Spring Web ni protocolos externos.

```java
// domain/event/port/EventCapturePort.java
public interface EventCapturePort {
    EventCaptureResult capture(CaptureCommand command);
    EventCaptureResult captureBatch(List<CaptureCommand> commands);
}

// domain/event/service/EventCaptureService.java (impl del port)
@Service
@RequiredArgsConstructor
@Transactional
class EventCaptureServiceImpl implements EventCapturePort {
    private final EventRepository eventRepo;
    private final ApplicationEventPublisher events;
    
    @Override
    public EventCaptureResult capture(CaptureCommand command) { ... }
}

// adapter/adms/AttlogTableProcessor.java (driving adapter)
@Component
@RequiredArgsConstructor
final class AttlogTableProcessor implements AdmsTableProcessor {
    private final EventCapturePort eventCapture;  // dependencia: el port, no el impl
    private final AdmsRecordTranslator translator;
    
    public AdmsProcessResult process(KioskoContext ctx, String stamp, byte[] body) {
        var commands = parse(body).stream().map(r -> translator.toCaptureCommand(ctx, r)).toList();
        var result = eventCapture.captureBatch(commands);
        return toAdmsResult(result);
    }
}
```

Verificación con ArchUnit:

```java
@ArchTest
static final ArchRule domain_no_depende_de_adapter =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..");

@ArchTest
static final ArchRule domain_no_usa_spring_web =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..", 
            "jakarta.servlet..");
```

## 2. Anti-Corruption Layer (ACL)

El vocabulario de ZKTeco (`SN`, `Stamp`, `OPERLOG`, `verify_mode`) **no se filtra** al dominio. El traductor es la única clase que cruza la frontera.

```java
// adapter/adms/translator/AdmsRecordTranslator.java — única que importa de ambos lados
@Component
final class AdmsRecordTranslator {
    public CaptureCommand toCaptureCommand(KioskoContext ctx, AttLogRecord r, Employee emp) {
        // ADMS verifyMode 1 → CaptureMethod.FINGERPRINT
        // ADMS status 0 → EventType.CHECK_IN
        // device timestamp + plant timezone → UTC Instant
        return CaptureCommand.builder()...build();
    }
}
```

Beneficios:
- Cambios en el firmware ADMS afectan **solo** al adapter.
- Migrar a Hikvision (v2) significa nuevo adapter, no tocar el core.
- Tests del dominio no necesitan saber de ADMS.

## 3. Service como interfaz + Implementation

**Regla**: services públicos se programan **siempre** contra interfaz.

```java
// domain/employee/service/EmployeeService.java
public interface EmployeeService {
    Employee create(CreateEmployeeCommand cmd);
    Employee findById(EmployeeId id);
    Page<Employee> search(EmployeeSearchCriteria criteria, Pageable pageable);
    void terminate(EmployeeId id, TerminationReason reason);
}

// domain/employee/service/EmployeeServiceImpl.java
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
class EmployeeServiceImpl implements EmployeeService {
    
    private final EmployeeRepository repo;
    private final EmployeeMapper mapper;
    private final ApplicationEventPublisher events;
    private final TenantContext tenantContext;
    
    @Override
    @Timed("employee.create")
    @TenantScoped
    public Employee create(CreateEmployeeCommand cmd) {
        validateBusinessRules(cmd);
        var entity = mapper.toEntity(cmd);
        var saved = repo.save(entity);
        events.publishEvent(new EmployeeCreated(saved.getId(), tenantContext.requireTenant(), saved.getDefaultPlantId()));
        return mapper.fromEntity(saved);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Employee findById(EmployeeId id) {
        return repo.findById(id.value())
            .map(mapper::fromEntity)
            .orElseThrow(() -> new EmployeeNotFoundException(id));
    }
    
    // ...
}
```

Beneficios:
- Tests unit puros con mocks de la interfaz.
- Decoración via `@Primary` (caching, metrics, logging) sin tocar callers.
- AOP de Spring requiere proxy → la interfaz lo permite limpio.

ArchUnit lo enforza:

```java
@ArchTest
static final ArchRule services_se_programan_contra_interfaz =
    classes().that().areAnnotatedWith(Service.class)
        .should().notBePublic()
        .orShould().beInterfaces();

@ArchTest
static final ArchRule service_impl_implementan_interfaz_correspondiente =
    classes().that().haveSimpleNameEndingWith("ServiceImpl")
        .should().implement(JavaClass.Predicates.simpleNameEndingWith("Service"));
```

## 4. Centralized Error Handling con @RestControllerAdvice

**Cero try/catch en controllers**. Las excepciones del dominio se traducen a HTTP en un único punto.

### Jerarquía de excepciones del dominio (sealed)

```java
public abstract sealed class DomainException extends RuntimeException
        permits BusinessRuleException, NotFoundException, ConflictException, 
                ValidationException, ForbiddenException {
    
    private final ErrorCode code;
    private final Map<String, Object> context;
    
    public ErrorCode code() { return code; }
    public Map<String, Object> context() { return context; }
}

public abstract non-sealed class NotFoundException extends DomainException { ... }
public abstract non-sealed class BusinessRuleException extends DomainException { ... }
public abstract non-sealed class ConflictException extends DomainException { ... }
public abstract non-sealed class ValidationException extends DomainException { ... }
public abstract non-sealed class ForbiddenException extends DomainException { ... }
```

### Excepciones concretas con contexto

```java
public final class EmployeeNotFoundException extends NotFoundException {
    public EmployeeNotFoundException(EmployeeId id) {
        super(ErrorCode.EMPLOYEE_NOT_FOUND, 
              "Employee " + id + " not found",
              Map.of("employee_id", id.value()));
    }
    
    public EmployeeNotFoundException(EmployeeNumber number) {
        super(ErrorCode.EMPLOYEE_NOT_FOUND,
              "Employee " + number + " not found",
              Map.of("employee_number", number.value()));
    }
}

public final class PayPeriodLockedException extends ConflictException { ... }
public final class CrossTenantAccessException extends ForbiddenException { ... }
public final class NfcCardBlockedException extends BusinessRuleException { ... }
```

### Códigos centralizados

```java
public enum ErrorCode {
    EMPLOYEE_NOT_FOUND(404, false),
    EMPLOYEE_TERMINATED(422, false),
    NFC_BLOCKED(422, false),
    KIOSKO_REVOKED(410, false),
    KIOSKO_PLANT_MISMATCH(422, false),
    PAY_PERIOD_LOCKED(422, false),
    PAY_PERIOD_INCOMPLETE(422, false),
    ETAG_MISMATCH(409, true),
    POLICY_VERSION_OUTDATED(409, true),
    CROSS_TENANT(403, false),
    VALIDATION_FAILED(422, false),
    RATE_LIMITED(429, true),
    UNKNOWN_ADMS_TABLE(400, false),
    DEVICE_COMMAND_NOT_FOUND(404, false);
    
    private final int httpStatus;
    private final boolean retriable;
}
```

### Handler global

```java
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
class GlobalExceptionHandler {
    
    private final ProblemDetailFactory problemFactory;
    private final MeterRegistry metrics;
    
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return toProblem(ex, req, HttpStatus.NOT_FOUND);
    }
    
    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessRuleException ex, HttpServletRequest req) {
        return toProblem(ex, req, HttpStatus.UNPROCESSABLE_ENTITY);
    }
    
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ConflictException ex, HttpServletRequest req) {
        return toProblem(ex, req, HttpStatus.CONFLICT);
    }
    
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ValidationException ex, HttpServletRequest req) {
        return toProblem(ex, req, HttpStatus.UNPROCESSABLE_ENTITY);
    }
    
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return toProblem(ex, req, HttpStatus.FORBIDDEN);
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleBeanValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest req) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldErrorDetail(fe.getField(), fe.getCode(), fe.getDefaultMessage()))
            .toList();
        return ResponseEntity.unprocessableEntity()
            .body(problemFactory.fromValidation(fieldErrors, req));
    }
    
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleRouteNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(404).body(problemFactory.routeNotFound(req));
    }
    
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error in {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        metrics.counter("api.error.5xx", "endpoint", req.getRequestURI()).increment();
        return ResponseEntity.internalServerError().body(problemFactory.serverError(req));
    }
    
    private ResponseEntity<ProblemDetail> toProblem(DomainException ex, HttpServletRequest req, HttpStatus status) {
        var problem = problemFactory.fromDomainException(ex, req, status);
        metrics.counter("api.error.4xx", "code", ex.code().name()).increment();
        return ResponseEntity.status(status).body(problem);
    }
}
```

ArchUnit verifica:

```java
@ArchTest
static final ArchRule controllers_no_usan_try_catch =
    noMethods().that().areDeclaredInClassesThat().resideInAPackage("..controller..")
        .should().beAnnotatedWith(...catch...)
        .because("Errores se manejan en @RestControllerAdvice");
```

## 5. Custom annotations + AOP aspects

Cinco preocupaciones transversales, cinco anotaciones.

### @TenantScoped

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {}

@Aspect @Component @RequiredArgsConstructor @Order(1)
class TenantScopedAspect {
    private final TenantContext tenantContext;
    private final EntityManager em;
    
    @Around("@annotation(TenantScoped) || @within(TenantScoped)")
    public Object applyTenantScope(ProceedingJoinPoint pjp) throws Throwable {
        var tenantId = tenantContext.requireTenant();
        em.unwrap(Session.class).doWork(connection -> {
            try (var st = connection.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            }
        });
        return pjp.proceed();
    }
}
```

### @Idempotent

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    long ttlSeconds() default 86400;
}

@Aspect @Component @RequiredArgsConstructor
class IdempotencyAspect {
    private final IdempotencyStore store;
    private final HttpServletRequest request;
    private final ObjectMapper mapper;
    
    @Around("@annotation(idem)")
    public Object enforceIdempotency(ProceedingJoinPoint pjp, Idempotent idem) throws Throwable {
        var key = request.getHeader("Idempotency-Key");
        if (key == null) throw new MissingIdempotencyKeyException();
        
        var hash = hashRequest(request);
        var cached = store.findByKey(key);
        
        if (cached.isPresent()) {
            if (!cached.get().requestHash().equals(hash)) {
                throw new IdempotencyConflictException(key);
            }
            return cached.get().response();
        }
        
        var result = pjp.proceed();
        store.save(key, hash, result, Duration.ofSeconds(idem.ttlSeconds()));
        return result;
    }
}
```

### @RequiresRole / @RequiresPlantAccess

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {
    Role[] value();
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlantAccess {
    String pathParam() default "plantId";
}

@Aspect @Component @RequiredArgsConstructor
class AuthorizationAspect {
    
    private final UserContext userContext;
    
    @Before("@annotation(requires)")
    public void enforceRole(JoinPoint jp, RequiresRole requires) {
        var user = userContext.currentUser();
        if (Arrays.stream(requires.value()).noneMatch(r -> user.role() == r)) {
            throw new ForbiddenRoleException(user.role(), requires.value());
        }
    }
    
    @Before("@annotation(requires)")
    public void enforcePlantAccess(JoinPoint jp, RequiresPlantAccess requires) {
        var user = userContext.currentUser();
        if (user.role() == Role.SUPER_ADMIN || user.role() == Role.TENANT_ADMIN) return;
        
        var plantId = extractPlantIdFromArgs(jp, requires.pathParam());
        if (!user.plantIds().contains(plantId)) {
            throw new CrossPlantAccessException(plantId, user.plantIds());
        }
    }
}
```

### @AuditedAction

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedAction {
    String action();
    String entityType() default "";
}

@Aspect @Component @RequiredArgsConstructor
class AuditAspect {
    private final AuditLogService auditLog;
    private final UserContext userContext;
    
    @AfterReturning(pointcut = "@annotation(audited)", returning = "result")
    public void logAction(JoinPoint jp, AuditedAction audited, Object result) {
        auditLog.record(AuditEntry.builder()
            .actorUserId(userContext.currentUserId())
            .action(audited.action())
            .entityType(audited.entityType())
            .entityId(extractEntityId(result))
            .occurredAt(Instant.now())
            .build());
    }
}
```

### @RateLimited

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    String key();
    int permits();
    int periodSeconds();
}

@Aspect @Component @RequiredArgsConstructor
class RateLimitingAspect {
    private final RateLimiterRegistry registry;
    
    @Around("@annotation(limit)")
    public Object enforce(ProceedingJoinPoint pjp, RateLimited limit) throws Throwable {
        var bucket = registry.resolve(limit.key(), limit.permits(), limit.periodSeconds());
        if (!bucket.tryConsume(1)) {
            throw new RateLimitedException(limit.key());
        }
        return pjp.proceed();
    }
}
```

Uso combinado:

```java
@PostMapping("/employees")
@RequiresRole({Role.TENANT_ADMIN, Role.RH})
@AuditedAction(action = "employee.create", entityType = "employee")
@Idempotent
@RateLimited(key = "tenant", permits = 600, periodSeconds = 60)
public ResponseEntity<EmployeeDTO> create(@Valid @RequestBody CreateEmployeeRequest req) {
    var employee = service.create(mapper.toDomain(req));
    return ResponseEntity.status(201).body(mapper.toDto(employee));
}
```

## 6. Mappers explícitos con MapStruct

```java
@Mapper(componentModel = "spring",
        uses = {EmployeeNumberMapper.class, ZoneIdMapper.class},
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface EmployeeMapper {
    
    @Mapping(target = "displayName", source = "fullName")
    EmployeeDTO toDto(Employee domain);
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "ACTIVE")
    Employee toDomain(CreateEmployeeRequest dto);
    
    Employee fromEntity(EmployeeEntity entity);
    EmployeeEntity toEntity(Employee domain);
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    void updateEntityFromDomain(Employee domain, @MappingTarget EmployeeEntity entity);
}
```

`unmappedTargetPolicy = ERROR` rompe build si un campo no fue mapeado. Cero bugs por olvido.

## 7. Validation con Bean Validation (Jakarta) + custom validators

```java
public record CreateEmployeeRequest(
    @NotBlank @Pattern(regexp = "^[A-Z0-9-]{4,20}$", message = "{employee.number.invalid}")
    String employeeNumber,
    
    @NotBlank @Size(max = 200) FullNameSafe
    String fullName,
    
    @ValidCurp String curp,
    @ValidRfc(physicalPerson = true) String rfc,
    @ValidNss String nss,
    
    @NotNull UUID defaultPlantId,
    UUID defaultShiftId,
    
    @Past @NotNull LocalDate hireDate,
    boolean multiPlantAllowed
) {}

@Constraint(validatedBy = CurpValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurp {
    String message() default "{validation.curp.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

class CurpValidator implements ConstraintValidator<ValidCurp, String> {
    private static final Pattern CURP = Pattern.compile(
        "^[A-Z][AEIOUX][A-Z]{2}\\d{6}[HM][A-Z]{2}[BCDFGHJKLMNÑPQRSTVWXYZ]{3}[0-9A-Z]\\d$");
    
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        return value == null || CURP.matcher(value).matches();
    }
}
```

## 8. Spring Data JPA Auditing

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
class AuditingConfig {
    
    @Bean
    AuditorAware<UUID> auditorAware(UserContext userContext) {
        return userContext::currentUserIdOptional;
    }
}

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
abstract class AuditableEntity {
    
    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;
    
    @LastModifiedDate
    private Instant updatedAt;
    
    @CreatedBy
    @Column(updatable = false)
    private UUID createdBy;
    
    @LastModifiedBy
    private UUID updatedBy;
}

@Entity
@Table(name = "employee")
class EmployeeEntity extends AuditableEntity {
    @Id
    private UUID id;
    
    @Column(unique = true)
    private String employeeNumber;
    
    // ... campos del dominio
}
```

Cero código en services para mantener `created_at`, `updated_by`, etc.

## 9. Configuration Properties tipados

```java
@ConfigurationProperties("app.payroll")
@Validated
public record PayrollProperties(
    @NotNull @Min(1) @Max(100) Integer maxExportSizeMb,
    @NotNull Duration exportTimeout,
    @NotNull String defaultExporterFormat,
    @NotNull Map<String, ExporterConfig> exporters
) {
    public record ExporterConfig(
        @NotNull String name,
        boolean enabled,
        @NotNull Map<String, String> options
    ) {}
}
```

```yaml
app:
  payroll:
    max-export-size-mb: 50
    export-timeout: PT5M
    default-exporter-format: csv-generic
    exporters:
      csv-generic:
        name: "CSV Genérico"
        enabled: true
        options:
          delimiter: ","
          quote-char: "\""
      contpaqi:
        name: "CONTPAQi"
        enabled: false
```

`@Validated` causa que Spring valide al arrancar. Configuración inválida = no boots.

## 10. Profiles + Conditional beans

Plan B (Windows Bridge) sin código duplicado:

```java
@Configuration
@ConditionalOnProperty(name = "app.adms.transport", havingValue = "http", matchIfMissing = true)
class HttpAdmsTransportConfig {
    @Bean AdmsTransport httpTransport() { return new HttpAdmsTransport(); }
}

@Configuration
@ConditionalOnProperty(name = "app.adms.transport", havingValue = "windows-bridge")
class WindowsBridgeTransportConfig {
    @Bean AdmsTransport windowsBridgeTransport() { return new WindowsBridgeTransport(); }
}
```

Por profile:

```java
@Configuration
@Profile("!prod")
class DevSeederConfig {
    @Bean CommandLineRunner seedDevData(...) { ... }
}
```

## 11. Resilience4j para llamadas externas

```java
@Service
@RequiredArgsConstructor
class CognitoClientImpl implements CognitoClient {
    
    private final CognitoIdentityProviderClient sdk;
    
    @Retry(name = "cognito", fallbackMethod = "verifyTokenFallback")
    @CircuitBreaker(name = "cognito")
    @TimeLimiter(name = "cognito")
    public CompletableFuture<JwtClaims> verifyToken(String token) {
        return CompletableFuture.supplyAsync(() -> sdk.verifyToken(token));
    }
    
    private CompletableFuture<JwtClaims> verifyTokenFallback(String token, Throwable t) {
        log.warn("Cognito unavailable: {}", t.getMessage());
        return CompletableFuture.completedFuture(jwtCache.get(token));
    }
}
```

```yaml
resilience4j:
  retry:
    instances:
      cognito:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
  circuitbreaker:
    instances:
      cognito:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 20
  timelimiter:
    instances:
      cognito:
        timeout-duration: 3s
```

## 12. Caching declarativo con Caffeine

```java
@Configuration
@EnableCaching
class CachingConfig {
    
    @Bean
    CacheManager cacheManager() {
        var manager = new CaffeineCacheManager(
            "kiosko-by-sn", "tenant-config", "policy-effective", 
            "employee-by-number", "nfc-active");
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .recordStats());
        return manager;
    }
}

@Service
@RequiredArgsConstructor
class PolicyResolverImpl implements PolicyResolver {
    
    @Cacheable(value = "policy-effective", key = "#plantId")
    public PolicySnapshot effectivePolicyFor(PlantId plantId) {
        return computeFromHistory(plantId);
    }
    
    @CacheEvict(value = "policy-effective", key = "#event.plantId()")
    @EventListener(PolicyChanged.class)
    public void onPolicyChanged(PolicyChanged event) {}
}
```

Métricas Caffeine se exportan a Micrometer automáticamente con `recordStats()`.

## 13. MDC structured logging

```java
@Component
class TraceMdcFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            MDC.put("trace_id", extractOrGenerateTraceId(req));
            MDC.put("request_id", UUID.randomUUID().toString());
            MDC.put("path", req.getRequestURI());
            MDC.put("method", req.getMethod());
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
    
    private String extractOrGenerateTraceId(HttpServletRequest req) {
        var traceId = req.getHeader("X-Trace-Id");
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}

@Component
class TenantMdcEnricher implements TenantContextListener {
    @Override public void onTenantSet(UUID tenantId) {
        MDC.put("tenant_id", tenantId.toString());
    }
    @Override public void onTenantClear() {
        MDC.remove("tenant_id");
    }
}
```

`logback-spring.xml`:

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <logLevel/>
                <loggerName/>
                <message/>
                <mdc/>
                <stackTrace>
                    <fieldName>error_stack</fieldName>
                </stackTrace>
                <pattern>
                    <pattern>{"app_name": "assistance-checker"}</pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

Resultado en CloudWatch:
```json
{
  "@timestamp": "2026-04-30T14:32:01.234Z",
  "level": "INFO",
  "logger_name": "EventCaptureServiceImpl",
  "message": "Event persisted",
  "tenant_id": "0194a000-...",
  "trace_id": "01HX2K9...",
  "request_id": "...",
  "path": "/api/v1/events",
  "method": "POST",
  "app_name": "assistance-checker"
}
```

## 14. Specifications para queries dinámicas

```java
public final class EmployeeSpecifications {
    
    public static Specification<EmployeeEntity> hasStatus(EmployeeStatus status) {
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }
    
    public static Specification<EmployeeEntity> belongsToPlant(UUID plantId) {
        return (root, q, cb) -> cb.equal(root.get("defaultPlantId"), plantId);
    }
    
    public static Specification<EmployeeEntity> nameContains(String search) {
        return (root, q, cb) -> cb.like(cb.lower(root.get("fullName")), 
                                         "%" + search.toLowerCase() + "%");
    }
    
    public static Specification<EmployeeEntity> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }
    
    private EmployeeSpecifications() {}
}

public interface EmployeeRepository extends JpaRepository<EmployeeEntity, UUID>,
                                              JpaSpecificationExecutor<EmployeeEntity> {}

@Service
@RequiredArgsConstructor
class EmployeeQueryServiceImpl implements EmployeeQueryService {
    
    private final EmployeeRepository repo;
    private final EmployeeMapper mapper;
    
    @Override
    public Page<Employee> search(EmployeeSearchCriteria c, Pageable pageable) {
        Specification<EmployeeEntity> spec = where(notDeleted())
            .and(c.status().map(EmployeeSpecifications::hasStatus).orElse(null))
            .and(c.plantId().map(EmployeeSpecifications::belongsToPlant).orElse(null))
            .and(c.search().map(EmployeeSpecifications::nameContains).orElse(null));
        
        return repo.findAll(spec, pageable).map(mapper::fromEntity);
    }
}
```

Queries dinámicas type-safe, composables, sin SQL hardcoded.

## 15. Domain Events con @TransactionalEventListener

Spring `ApplicationEventPublisher` para desacoplar bounded contexts.

```java
// Publicación
@Service
@RequiredArgsConstructor
@Transactional
class EmployeeServiceImpl implements EmployeeService {
    
    private final ApplicationEventPublisher events;
    
    @Override
    public Employee create(CreateEmployeeCommand cmd) {
        var saved = repo.save(buildEntity(cmd));
        events.publishEvent(new EmployeeCreated(
            EmployeeId.of(saved.getId()),
            TenantId.of(saved.getTenantId()),
            PlantId.of(saved.getDefaultPlantId()),
            EmployeeNumber.of(saved.getEmployeeNumber())
        ));
        return mapper.fromEntity(saved);
    }
}

// Consumo en otros bounded contexts
@Component
@RequiredArgsConstructor
class DeviceCommandQueueListener {
    
    private final DeviceCommandService commandService;
    private final KioskoRepository kioskoRepo;
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmployeeCreated(EmployeeCreated event) {
        kioskoRepo.findActiveByPlant(event.plantId())
            .stream()
            .map(k -> buildAddUserCommand(k.id(), event))
            .forEach(commandService::enqueue);
    }
}

@Component
class AuditTrailListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAnyDomainEvent(DomainEvent event) { /* registra en audit */ }
}
```

`AFTER_COMMIT`: si la transacción del publisher falla, los listeners no corren.

## 16. Sealed types + pattern matching (Java 21)

Para jerarquías cerradas donde queremos exhaustividad del compilador.

```java
public sealed interface ProcessingOutcome permits Persisted, Skipped, Failed {}
public record Persisted(EventId id, boolean wasDuplicate) implements ProcessingOutcome {}
public record Skipped(SkipReason reason, String detail) implements ProcessingOutcome {}
public record Failed(ProcessingError error) implements ProcessingOutcome {}

// Pattern matching exhaustivo
String describe(ProcessingOutcome outcome) {
    return switch (outcome) {
        case Persisted p when p.wasDuplicate() -> "Duplicado: " + p.id();
        case Persisted p -> "Persistido: " + p.id();
        case Skipped s -> "Saltado: " + s.reason();
        case Failed f -> "Fallido: " + f.error().message();
        // sin default — sealed garantiza exhaustividad
    };
}
```

Agregar `Quarantined` rompe compilación en cada switch hasta actualizar.

## 17. Value Objects (eliminación de primitive obsession)

```java
public record EmployeeNumber(String value) {
    public EmployeeNumber {
        Objects.requireNonNull(value);
        if (!value.matches("^[A-Z0-9-]{4,20}$")) {
            throw new InvalidEmployeeNumberException(value);
        }
    }
    public static EmployeeNumber of(String v) { return new EmployeeNumber(v); }
}

public record TenantId(UUID value) {
    public static TenantId of(UUID v) { return new TenantId(v); }
    public static TenantId fromString(String v) { return new TenantId(UUID.fromString(v)); }
}

public record EventId(UUID value) implements Serializable {
    public static EventId of(UUID v) { return new EventId(v); }
    public static EventId generate() { return new EventId(UuidGenerator.v7()); }
}

public record EmailAddress(String value) {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    
    public EmailAddress {
        Objects.requireNonNull(value);
        if (!EMAIL.matcher(value).matches()) throw new InvalidEmailException(value);
    }
}
```

Sin `String pin`, `UUID id`, `int status` sueltos. El tipo lleva la validación.

## 18. Builder pattern

Para DTOs/Commands con muchos campos opcionales, usar Lombok `@Builder` o records con builder manual.

```java
@Builder
public record CaptureCommand(
    EventId id,
    TenantId tenantId,
    PlantId plantId,
    KioskoId kioskoId,
    EmployeeId employeeId,
    EventType eventType,
    CaptureMethod captureMethod,
    Instant deviceTime,
    int deviceOffsetMs,
    Instant eventTime,
    @Nullable NfcCardId nfcCardId,
    @Nullable Map<String, Object> rawPayload
) {
    // Constructor compacto valida invariantes
    public CaptureCommand {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(eventTime);
        // ...
    }
}
```

## 19. Constructor injection + final fields (no @Autowired field)

```java
// ✅ Constructor injection con Lombok
@Service
@RequiredArgsConstructor
class EmployeeServiceImpl implements EmployeeService {
    private final EmployeeRepository repo;
    private final EmployeeMapper mapper;
    private final ApplicationEventPublisher events;
}

// ❌ Field injection (prohibido)
@Service
class EmployeeServiceImpl implements EmployeeService {
    @Autowired private EmployeeRepository repo;  // NO
}
```

ArchUnit:
```java
@ArchTest
static final ArchRule no_field_injection =
    noFields().should().beAnnotatedWith(Autowired.class)
        .because("Use constructor injection con final fields");
```

## 20. Resumen del stack arquitectónico

```
HTTP LAYER                        Spring MVC + Bean Validation + ProblemDetail
ERROR HANDLING                    @RestControllerAdvice global
AOP CROSS-CUTTING                 @TenantScoped, @Idempotent, @RequiresRole, 
                                  @AuditedAction, @RateLimited
APPLICATION                       Service interface + Impl, @Transactional
DOMAIN                            Entities + Value Objects + Sealed types + Domain Events
PERSISTENCE                       JPA + Spring Data + Specifications + JPA Auditing
INFRASTRUCTURE                    MapStruct + Resilience4j + Caffeine + MDC
EXTERNAL                          Cognito + S3 + KMS + SES (todos via interfaz)
TESTING                           JUnit + Mockito + WebMvcTest + Testcontainers + ArchUnit
```

Cada uno de los patrones arriba está implementado de la manera más sostenible para un equipo de Java/Spring Boot enterprise: extensible, testable, type-safe, y verificable con ArchUnit en CI.

Ver:
- [12-push-adapter.md](./12-push-adapter.md) — aplicación concreta de estos patrones al adapter ADMS.
- [14-test-architecture.md](./14-test-architecture.md) — pirámide de tests + reglas ArchUnit completas.
- [adrs/ADR-006-arquitectura-aplicacion.md](./adrs/ADR-006-arquitectura-aplicacion.md) — decisión formal.

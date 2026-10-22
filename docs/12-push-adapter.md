# Push Adapter ZKTeco — diseño detallado

Este documento describe el módulo del monolito que recibe el protocolo ADMS de ZKTeco K50 Pro y lo traduce al modelo interno. Diseñado bajo los patrones formalizados en [13-patrones-arquitectonicos.md](./13-patrones-arquitectonicos.md) y [ADR-006](./adrs/ADR-006-arquitectura-aplicacion.md).

> ⚠ Hay puntos del diseño marcados como `[VALIDAR EN SPIKE]`. Dependen del firmware real del K50 Pro recibido en W3-W4. El TL completa esos huecos en `docs/spikes/ADMS-K50Pro-resultados.md` antes de Sprint 5.

## 1. Posicionamiento del módulo

El Push Adapter es un **driving adapter** de la arquitectura hexagonal. Vive en `com.assistance.adapter.adms.*` y satisface el puerto `EventCapturePort` del dominio.

```
                          ┌─────────────────────────┐
ZKTeco K50 Pro ─►push────► │   ADAPTER ADMS         │ ─►EventCapturePort──► EventService (core)
                          │   (sucio: HTTP, texto,  │
Web Kiosko ───►rest──────► │    formato propietario)│ ─►DeviceCommandSink──► CommandService
                          │                         │
                          └─────────────────────────┘
                                      ▲
                                      │
                              Anti-Corruption Layer
                              (vocabulario ZKTeco no
                               cruza al core)
```

Reglas estructurales (verificadas con ArchUnit):

- `domain/*` no importa `adapter/adms/*`.
- `adapter/adms/*` no importa `adapter/web/*`.
- Solo `AdmsRecordTranslator` importa simultáneamente desde `adapter/adms/parser` y `domain/event`.

## 2. Decisiones operativas integradas

| Decisión | Valor | Razón |
|---|---|---|
| Modo de comunicación | `Realtime=1` con métricas | Validar en piloto si justifica cambiar a batch |
| Empleado fantasma | Skip + log + alerta | Permite reconciliación, no bloquea batch |
| Encoding | `kiosko_zkteco.encoding` configurable | UTF-8 default, override si firmware difiere |
| Persistencia batch | Transaccional + dead letter | Consistencia + tolerancia a registros malformados |

## 3. Endpoints expuestos

Todos bajo `/iclock/*`. Sin Bearer JWT. Validación por `Serial Number` del aparato.

```
GET    /iclock/cdata?SN=...&options=all
       Device pide configuración inicial
       
POST   /iclock/cdata?SN=...&table=ATTLOG&Stamp=...
       Device empuja eventos de asistencia
       
POST   /iclock/cdata?SN=...&table=OPERLOG&Stamp=...
       Device empuja operaciones (alta/baja de usuarios, huellas) [v1.1]
       
GET    /iclock/getrequest?SN=...&INFO=...
       Device pregunta por comandos pendientes
       
POST   /iclock/devicecmd?SN=...
       Device confirma ejecución de comando
       
POST   /iclock/edata?SN=...&type=fp
       Device sube template biométrica [v1.1]
```

## 4. Estructura de paquetes

```
com.assistance.adapter.adms/
├── controller/
│   └── AdmsController.java                    (HTTP layer thin, sin try/catch)
├── auth/
│   ├── DeviceAuthFilter.java                  (extrae SN, resuelve Kiosko)
│   ├── KioskoContext.java                     (record inmutable)
│   └── KioskoLookupService.java + Impl        (cacheado con @Cacheable)
├── parser/
│   ├── AdmsAttlogParser.java
│   ├── AdmsOperlogParser.java
│   ├── AttLogRecord.java                      (record DTO)
│   └── OperLogRecord.java                     (record DTO)
├── translator/
│   └── AdmsRecordTranslator.java              (única clase que cruza adapter ↔ domain)
├── processor/
│   ├── AdmsTableProcessor.java                (interface)
│   ├── AttlogTableProcessor.java              (impl)
│   ├── OperlogTableProcessor.java             (impl)
│   ├── AttPhotoTableProcessor.java            (impl v2)
│   └── AdmsTableProcessorRegistry.java
├── command/
│   ├── DeviceCommand.java                     (sealed interface)
│   ├── AddUser.java, DeleteUser.java, ...     (records)
│   ├── DeviceCommandVisitor.java              (interface visitor)
│   ├── AdmsSerializerVisitor.java             (impl: command → string ADMS)
│   └── CommandValidatorVisitor.java           (impl: command → validation)
├── pipeline/
│   ├── ProcessingStep.java                    (interface)
│   ├── ProcessingContext.java
│   ├── DecodeBytesStep.java                   (@Order(10))
│   ├── ParseAdmsStep.java                     (@Order(20))
│   ├── ResolveEmployeeStep.java               (@Order(30))
│   ├── ApplyValidationStep.java               (@Order(40))
│   ├── EnrichEventStep.java                   (@Order(50))
│   ├── PersistStep.java                       (@Order(60))
│   ├── PublishDomainEventStep.java            (@Order(70))
│   └── ProcessingPipeline.java                (orchestrador)
├── outcome/
│   ├── ProcessingOutcome.java                 (sealed: Persisted, Skipped, Failed)
│   └── ProcessingError.java                   (sealed: ParseError, ValidationError, ...)
├── service/
│   ├── AdmsConfigService.java + Impl          (genera respuesta de getrequest)
│   ├── DeviceCommandService.java + Impl       (drain queue + ack)
│   ├── DeviceStateService.java + Impl         (heartbeat, last_seen)
│   ├── DeviceStampService.java + Impl         (cursor management)
│   ├── DeadLetterService.java + Impl
│   └── DeviceOrphanService.java + Impl
├── listener/
│   ├── DeviceCommandQueueListener.java        (@EventListener: org. → device cmd)
│   └── KioskoOfflineDetector.java             (@Scheduled)
└── config/
    ├── AdmsProperties.java                    (@ConfigurationProperties)
    └── AdmsCachingConfig.java                 (Caffeine config)

com.assistance.domain.event/
├── port/
│   ├── EventCapturePort.java                  (interface)
│   └── DeviceCommandSink.java                 (interface)
├── service/
│   ├── EventCaptureService.java               (interface)
│   └── EventCaptureServiceImpl.java
├── model/
│   ├── Event.java                             (aggregate)
│   ├── EventId.java                           (value object)
│   └── EmployeeNumber.java                    (value object)
└── error/
    ├── EmployeeNotFoundException.java
    ├── EmployeeTerminatedException.java
    └── NfcCardBlockedException.java
```

## 5. Controller (capa HTTP delgada)

Sin try/catch. Sin lógica de negocio. Solo orquesta.

```java
@RestController
@RequestMapping("/iclock")
@RequiredArgsConstructor
class AdmsController {
    
    private final AdmsTableProcessorRegistry registry;
    private final AdmsConfigService configService;
    private final DeviceCommandService commandService;
    private final DeviceStateService stateService;
    private final AdmsResponseFormatter formatter;
    
    @GetMapping(value = "/cdata", produces = MediaType.TEXT_PLAIN_VALUE)
    String handleInitialConfig(@RequestParam("SN") String sn,
                                @RequestParam(value = "options", required = false) String options) {
        var ctx = stateService.touchHeartbeat(sn);
        return formatter.formatInitialConfig(configService.buildFor(ctx));
    }
    
    @PostMapping(value = "/cdata", consumes = MediaType.TEXT_PLAIN_VALUE)
    String handleDataPush(@RequestParam("SN") String sn,
                          @RequestParam("table") String table,
                          @RequestParam(value = "Stamp", required = false) String stamp,
                          @RequestBody byte[] body) {
        var ctx = stateService.touchHeartbeat(sn);
        var processor = registry.resolve(table)
            .orElseThrow(() -> new UnknownAdmsTableException(table));
        var result = processor.process(ctx, stamp, body);
        return formatter.formatProcessResponse(result);
    }
    
    @GetMapping(value = "/getrequest", produces = MediaType.TEXT_PLAIN_VALUE)
    String handleCommandPoll(@RequestParam("SN") String sn,
                              @RequestParam(value = "INFO", required = false) String info) {
        var ctx = stateService.touchHeartbeat(sn);
        var commands = commandService.drainPending(ctx.kioskoId(), 50);
        return formatter.formatCommands(commands);
    }
    
    @PostMapping(value = "/devicecmd", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String handleCommandAck(@RequestParam("SN") String sn,
                             @RequestParam Map<String, String> ackPayload) {
        var ctx = stateService.touchHeartbeat(sn);
        commandService.acknowledge(ctx.kioskoId(), ackPayload);
        return "OK";
    }
    
    // /edata viene en v1.1
}
```

Errores se manejan en el `@RestControllerAdvice` global (ver [13-patrones-arquitectonicos.md](./13-patrones-arquitectonicos.md)). El controller no sabe ni quiere saber.

## 6. DeviceAuthFilter (resolución de identidad)

```java
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class DeviceAuthFilter extends OncePerRequestFilter {
    
    private final KioskoLookupService lookup;
    private final TenantContext tenantContext;
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/iclock/");
    }
    
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var sn = req.getParameter("SN");
        if (sn == null || sn.isBlank()) {
            res.setStatus(404);  // protocolo ADMS espera 404 para SNs no reconocidos
            return;
        }
        
        var ctx = lookup.findActive(sn).orElse(null);
        if (ctx == null) {
            res.setStatus(404);
            return;
        }
        
        try {
            tenantContext.set(ctx.tenantId(), ctx.kioskoId());
            req.setAttribute("kioskoContext", ctx);
            chain.doFilter(req, res);
        } finally {
            tenantContext.clear();
        }
    }
}

// Service de lookup cacheado
public interface KioskoLookupService {
    Optional<KioskoContext> findActive(String serialNumber);
    void invalidateCache(String serialNumber);
}

@Service
@RequiredArgsConstructor
class KioskoLookupServiceImpl implements KioskoLookupService {
    
    private final KioskoRepository repo;
    private final KioskoContextMapper mapper;
    
    @Override
    @Cacheable(value = "kiosko-by-sn", key = "#serialNumber", unless = "#result.isEmpty()")
    public Optional<KioskoContext> findActive(String serialNumber) {
        // Resuelve kiosko + kiosko_zkteco vía JOIN. El SN vive en la subtabla.
        // Ver query en 03-modelo-datos.md §4.7.
        return repo.findActiveZktecoByDeviceSerial(serialNumber)
            .map(mapper::toContext);
    }
    
    @Override
    @CacheEvict(value = "kiosko-by-sn", key = "#serialNumber")
    public void invalidateCache(String serialNumber) {}
}
```

Cache TTL 60s. Si admin marca un kiosko como `retired`, listener invalida cache.

## 7. AdmsTableProcessor (Strategy + Registry)

```java
public interface AdmsTableProcessor {
    String tableName();
    AdmsProcessResult process(KioskoContext ctx, String stamp, byte[] rawBody);
}

@Component
@RequiredArgsConstructor
@Slf4j
final class AttlogTableProcessor implements AdmsTableProcessor {
    
    private final ProcessingPipeline pipeline;
    private final DeviceStampService stampService;
    
    @Override
    public String tableName() { return "ATTLOG"; }
    
    @Override
    @TenantScoped
    @Transactional
    public AdmsProcessResult process(KioskoContext ctx, String stamp, byte[] rawBody) {
        var processingCtx = ProcessingContext.attlog(ctx, stamp, rawBody);
        var result = pipeline.run(processingCtx);
        var newStamp = stampService.advance(ctx.kioskoId(), tableName(), result.processedCount());
        return AdmsProcessResult.of(result, newStamp);
    }
}

@Component
@RequiredArgsConstructor
final class AdmsTableProcessorRegistry {
    
    private final Map<String, AdmsTableProcessor> byName;
    
    public AdmsTableProcessorRegistry(List<AdmsTableProcessor> processors) {
        this.byName = processors.stream()
            .collect(Collectors.toUnmodifiableMap(
                AdmsTableProcessor::tableName, Function.identity()));
    }
    
    public Optional<AdmsTableProcessor> resolve(String tableName) {
        return Optional.ofNullable(byName.get(tableName));
    }
}
```

Agregar `BIODATA` en v1.1: nueva clase `@Component BiodataTableProcessor implements AdmsTableProcessor`. Cero modificación en otros componentes.

## 8. ProcessingPipeline (Chain of Responsibility)

```java
public interface ProcessingStep {
    int order();
    StepResult execute(ProcessingContext ctx);
}

@Component @Order(10)
final class DecodeBytesStep implements ProcessingStep {
    public int order() { return 10; }
    public StepResult execute(ProcessingContext ctx) {
        var encoding = ctx.kioskoContext().encoding();
        ctx.setDecodedBody(new String(ctx.rawBody(), encoding));
        return StepResult.continueProcessing();
    }
}

@Component @Order(20)
@RequiredArgsConstructor
final class ParseAdmsStep implements ProcessingStep {
    private final AdmsAttlogParser parser;
    public int order() { return 20; }
    public StepResult execute(ProcessingContext ctx) {
        ctx.decodedBody().lines()
            .filter(line -> !line.isBlank())
            .forEach(line -> {
                parser.tryParse(line)
                    .ifPresentOrElse(
                        ctx::addParsedRecord,
                        () -> ctx.addDeadLetter(new ParseError(line, "malformed"))
                    );
            });
        return StepResult.continueProcessing();
    }
}

@Component @Order(30)
@RequiredArgsConstructor
final class ResolveEmployeeStep implements ProcessingStep {
    private final EmployeeDirectory directory;
    public int order() { return 30; }
    public StepResult execute(ProcessingContext ctx) {
        for (var record : ctx.parsedRecords()) {
            directory.findActive(ctx.tenantId(), record.pin())
                .ifPresentOrElse(
                    record::attachEmployee,
                    () -> ctx.addOrphan(record)
                );
        }
        return StepResult.continueProcessing();
    }
}

// ... resto de steps

@Service
@RequiredArgsConstructor
class ProcessingPipeline {
    
    private final List<ProcessingStep> steps;  // Spring inyecta ordenado por @Order
    private final DeadLetterService deadLetterService;
    private final DeviceOrphanService orphanService;
    
    public PipelineResult run(ProcessingContext ctx) {
        for (var step : steps) {
            var result = step.execute(ctx);
            if (result.shortCircuit()) break;
        }
        flushSideEffects(ctx);
        return ctx.toResult();
    }
    
    private void flushSideEffects(ProcessingContext ctx) {
        if (!ctx.deadLetters().isEmpty()) deadLetterService.recordAll(ctx.deadLetters());
        if (!ctx.orphans().isEmpty()) orphanService.recordAll(ctx.orphans());
    }
}
```

Agregar paso (`AntiFraudCheckStep` en v2): `@Component @Order(45)`. Pipeline se reordena automáticamente.

## 9. AdmsRecordTranslator (la única clase que cruza adapter ↔ domain)

```java
@Component
@RequiredArgsConstructor
final class AdmsRecordTranslator {
    
    private static final UUID ADMS_EVENT_NAMESPACE = 
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    
    public CaptureCommand toCaptureCommand(KioskoContext ctx, AttLogRecord r, Employee employee) {
        var plantTimezone = ctx.plantTimezone();
        var utcInstant = r.deviceTime().atZone(plantTimezone).toInstant();
        
        var eventId = deriveDeterministicId(ctx.serialNumber(), r.pin(), utcInstant, r.status());
        var eventType = EventType.fromAdms(r.status())
            .orElseThrow(() -> new InvalidEventTypeException(r.status()));
        var captureMethod = CaptureMethod.fromAdms(r.verifyMode())
            .orElseThrow(() -> new InvalidCaptureMethodException(r.verifyMode()));
        
        return CaptureCommand.builder()
            .id(eventId)
            .tenantId(ctx.tenantId())
            .plantId(ctx.plantId())
            .kioskoId(ctx.kioskoId())
            .employeeId(employee.id())
            .eventType(eventType)
            .captureMethod(captureMethod)
            .deviceTime(utcInstant)
            .deviceOffsetMs(0)  // K50 envía hora ya correcta
            .eventTime(utcInstant)
            .rawPayload(serializeRecord(r))
            .build();
    }
    
    private EventId deriveDeterministicId(String sn, String pin, Instant time, int status) {
        var key = sn + "|" + pin + "|" + time + "|" + status;
        var bytes = (ADMS_EVENT_NAMESPACE + key).getBytes(StandardCharsets.UTF_8);
        return EventId.of(UUID.nameUUIDFromBytes(bytes));
    }
}
```

Idempotencia natural: mismo evento físico → mismo UUID → `INSERT ... ON CONFLICT DO NOTHING` no duplica.

## 10. DeviceCommand con Visitor pattern + sealed types

```java
public sealed interface DeviceCommand permits 
        AddUser, DeleteUser, UpdateUser, BlockCard, UnblockCard, 
        UpdateFingertmp, ReloadConfig, Reboot {
    
    long id();
    UUID kioskoId();
    Instant createdAt();
    
    <R> R accept(DeviceCommandVisitor<R> visitor);
}

public record AddUser(
    long id,
    UUID kioskoId,
    Instant createdAt,
    EmployeeNumber pin,
    FullName name,
    Privilege privilege
) implements DeviceCommand {
    public <R> R accept(DeviceCommandVisitor<R> v) { return v.visit(this); }
}

public record DeleteUser(
    long id, UUID kioskoId, Instant createdAt, EmployeeNumber pin
) implements DeviceCommand {
    public <R> R accept(DeviceCommandVisitor<R> v) { return v.visit(this); }
}

// ... resto de records

public interface DeviceCommandVisitor<R> {
    R visit(AddUser cmd);
    R visit(DeleteUser cmd);
    R visit(UpdateUser cmd);
    R visit(BlockCard cmd);
    R visit(UnblockCard cmd);
    R visit(UpdateFingertmp cmd);
    R visit(ReloadConfig cmd);
    R visit(Reboot cmd);
}

@Component
@RequiredArgsConstructor
final class AdmsSerializerVisitor implements DeviceCommandVisitor<String> {
    
    public String visit(AddUser c) {
        return "C:%d:DATA UPDATE USERINFO PIN=%s\tName=%s\tPri=%d\tPasswd=".formatted(
            c.id(), c.pin().value(), c.name().value(), c.privilege().code());
    }
    
    public String visit(DeleteUser c) {
        return "C:%d:DATA DELETE USERINFO PIN=%s".formatted(c.id(), c.pin().value());
    }
    
    public String visit(UpdateUser c) { /* ... */ }
    public String visit(BlockCard c) { /* ... */ }
    public String visit(UnblockCard c) { /* ... */ }
    public String visit(UpdateFingertmp c) { /* ... */ }
    public String visit(ReloadConfig c) { return "C:%d:CHECK".formatted(c.id()); }
    public String visit(Reboot c) { return "C:%d:REBOOT".formatted(c.id()); }
}
```

Agregar comportamiento futuro (`HikvisionSerializerVisitor`, `CommandValidatorVisitor`, `CommandReplicatorVisitor`): nueva clase implementando `DeviceCommandVisitor<R>`. Cero modificación en commands ni en otros visitors.

## 11. ProcessingOutcome y ProcessingError (sealed Result types)

```java
public sealed interface ProcessingOutcome permits Persisted, Skipped, Failed {}
public record Persisted(EventId eventId, boolean wasDuplicate) implements ProcessingOutcome {}
public record Skipped(SkipReason reason, String detail) implements ProcessingOutcome {}
public record Failed(ProcessingError error) implements ProcessingOutcome {}

public sealed interface ProcessingError permits 
        ParseError, ValidationError, EmployeeNotFoundError, NfcCardBlockedError {
    String errorClass();
    String message();
    String rawLine();
}

public record ParseError(String rawLine, String message) implements ProcessingError {
    public String errorClass() { return "PARSE_ERROR"; }
}

public record ValidationError(String rawLine, String message, List<FieldError> fields) 
        implements ProcessingError {
    public String errorClass() { return "VALIDATION"; }
}

// Pattern matching exhaustivo en consumidor
for (var outcome : results) {
    switch (outcome) {
        case Persisted p when p.wasDuplicate() -> stats.incrementDuplicate();
        case Persisted p -> stats.incrementPersisted();
        case Skipped s -> orphanService.record(ctx, s);
        case Failed f -> deadLetterService.record(ctx, f.error());
    }
}
```

Compilador exige cubrir todos los casos. Agregar `Quarantined` (v2) rompe compilación en cada consumer hasta que se actualice.

## 12. DeviceCommandService (queue management)

```java
public interface DeviceCommandService {
    List<DeviceCommand> drainPending(KioskoId kioskoId, int limit);
    void acknowledge(KioskoId kioskoId, Map<String, String> ackPayload);
    void enqueue(DeviceCommand command);
}

@Service
@RequiredArgsConstructor
@Transactional
class DeviceCommandServiceImpl implements DeviceCommandService {
    
    private final DeviceCommandRepository repo;
    private final DeviceCommandMapper mapper;
    private final ApplicationEventPublisher events;
    
    @Override
    public List<DeviceCommand> drainPending(KioskoId kioskoId, int limit) {
        var entities = repo.findPendingForKioskoOrderByCreatedAt(kioskoId, limit);
        entities.forEach(e -> e.markSent(Instant.now()));
        return entities.stream().map(mapper::toDomain).toList();
    }
    
    @Override
    public void acknowledge(KioskoId kioskoId, Map<String, String> ack) {
        var cmdId = Long.parseLong(ack.get("ID"));
        var returnCode = Integer.parseInt(ack.get("Return"));
        
        var entity = repo.findByIdAndKioskoId(cmdId, kioskoId)
            .orElseThrow(() -> new DeviceCommandNotFoundException(cmdId));
        
        if (returnCode == 0) {
            entity.markAcked(Instant.now());
            events.publishEvent(new DeviceCommandAcked(cmdId, kioskoId));
        } else {
            entity.markFailed("device returned: " + returnCode);
            events.publishEvent(new DeviceCommandFailed(cmdId, kioskoId, returnCode));
        }
    }
    
    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enqueue(DeviceCommand command) {
        repo.save(mapper.toEntity(command));
    }
}
```

## 13. DeviceCommandQueueListener (Domain Events listener)

Reacciona a cambios en otros bounded contexts y encola comandos sin acoplamiento directo.

```java
@Component
@RequiredArgsConstructor
@Slf4j
class DeviceCommandQueueListener {
    
    private final KioskoRepository kioskoRepo;
    private final DeviceCommandService commandService;
    private final DeviceCommandFactory factory;
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmployeeCreated(EmployeeCreated event) {
        kioskoRepo.findActiveByPlant(event.plantId())
            .stream()
            .filter(k -> k.type() == KioskoType.ZKTECO_K50)
            .map(k -> factory.addUser(k.id(), event))
            .forEach(commandService::enqueue);
    }
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEmployeeTerminated(EmployeeTerminated event) {
        kioskoRepo.findActiveByPlant(event.plantId())
            .stream()
            .filter(k -> k.type() == KioskoType.ZKTECO_K50)
            .map(k -> factory.deleteUser(k.id(), event.employeeNumber()))
            .forEach(commandService::enqueue);
    }
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onNfcCardBlocked(NfcCardBlocked event) { /* ... */ }
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPolicyChanged(PolicyChanged event) {
        kioskoRepo.findActiveByPlant(event.plantId())
            .stream()
            .map(k -> factory.reloadConfig(k.id()))
            .forEach(commandService::enqueue);
    }
}
```

`@TransactionalEventListener(phase = AFTER_COMMIT)` garantiza que solo encolamos comandos si la transacción que disparó el evento se commiteó.

## 14. Servicios de soporte

### DeviceStateService

```java
public interface DeviceStateService {
    KioskoContext touchHeartbeat(String serialNumber);
    void detectOfflineKioskos(Duration threshold);
}

@Service
@RequiredArgsConstructor
class DeviceStateServiceImpl implements DeviceStateService {
    
    private final KioskoLookupService lookup;
    private final KioskoRepository repo;
    private final NotificationService notifications;
    
    @Override
    public KioskoContext touchHeartbeat(String sn) {
        var ctx = lookup.findActive(sn)
            .orElseThrow(() -> new KioskoNotFoundException(sn));
        repo.updateLastHeartbeat(ctx.kioskoId(), Instant.now());
        return ctx;
    }
    
    @Override
    @Scheduled(fixedDelayString = "${app.adms.offline-detection-interval:PT5M}")
    public void detectOfflineKioskos() {
        var threshold = Instant.now().minus(Duration.ofMinutes(15));
        repo.findActiveWithLastSeenBefore(threshold)
            .stream()
            .filter(k -> !k.offlineAlertSent())
            .forEach(this::alertOffline);
    }
    
    private void alertOffline(Kiosko k) {
        notifications.alertSupervisor(k.plantId(), 
            "Kiosko " + k.name() + " sin reportar desde " + k.lastHeartbeatAt());
        repo.markOfflineAlertSent(k.id());
    }
}
```

### DeviceStampService

```java
public interface DeviceStampService {
    long advance(KioskoId kioskoId, String tableName, int processedCount);
    long currentStamp(KioskoId kioskoId, String tableName);
}

@Service
@RequiredArgsConstructor
@Transactional
class DeviceStampServiceImpl implements DeviceStampService {
    
    private final DeviceStampRepository repo;
    
    @Override
    public long advance(KioskoId kioskoId, String tableName, int processedCount) {
        var stamp = repo.findOrCreate(kioskoId, tableName);
        stamp.advance(processedCount);
        return stamp.value();
    }
    
    @Override
    @Transactional(readOnly = true)
    public long currentStamp(KioskoId kioskoId, String tableName) {
        return repo.find(kioskoId, tableName).map(DeviceStamp::value).orElse(0L);
    }
}
```

## 15. Configuration Properties

```java
@ConfigurationProperties("app.adms")
@Validated
public record AdmsProperties(
    @NotNull AdmsDefaultsProperties defaults,
    @NotNull @Min(60) @Max(3600) Integer offlineDetectionMinutes,
    @NotNull @Min(1) @Max(500) Integer maxBatchSize,
    @NotNull @Min(1) @Max(100) Integer maxCommandsPerPoll
) {
    public record AdmsDefaultsProperties(
        boolean realtime,
        @NotNull @Min(1) Integer delaySeconds,
        @NotNull String encoding,
        @NotNull @Min(1) Integer transIntervalSeconds
    ) {}
}
```

```yaml
app:
  adms:
    defaults:
      realtime: true
      delay-seconds: 10
      encoding: UTF-8
      trans-interval-seconds: 1
    offline-detection-minutes: 15
    max-batch-size: 200
    max-commands-per-poll: 50
```

## 16. Schema completo del módulo

Tablas adicionales sobre el modelo de datos base:

```sql
-- Cola de comandos server → device
CREATE TABLE device_command (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    command_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('pending','sent','acked','failed')) DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    acked_at TIMESTAMPTZ,
    error_detail TEXT
);

CREATE INDEX idx_device_command_pending 
    ON device_command(kiosko_id, status, created_at)
    WHERE status = 'pending';

-- Cursor por (kiosko, tabla)
CREATE TABLE device_stamp (
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    table_name TEXT NOT NULL,
    last_stamp BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kiosko_id, table_name)
);

-- Empleados huérfanos detectados
CREATE TABLE device_orphan_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    device_pin TEXT NOT NULL,
    raw_record JSONB NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved BOOLEAN NOT NULL DEFAULT false,
    resolved_by UUID REFERENCES app_user(id),
    resolved_at TIMESTAMPTZ,
    resolution TEXT
);

CREATE INDEX idx_orphan_unresolved 
    ON device_orphan_log(tenant_id, resolved, detected_at) 
    WHERE resolved = false;

-- Eventos malformados o que fallaron procesamiento
CREATE TABLE dead_letter_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    raw_payload TEXT NOT NULL,
    parsed_record JSONB,
    error_class TEXT NOT NULL,
    error_message TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retried_count INT NOT NULL DEFAULT 0,
    last_retry_at TIMESTAMPTZ,
    resolved BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_dlq_unresolved 
    ON dead_letter_event(tenant_id, resolved, occurred_at)
    WHERE resolved = false;

-- Nota: encoding y offline_alert_sent ya viven en el modelo base
-- (ver 03-modelo-datos.md §4):
--   encoding             → kiosko_zkteco (es ADMS-specific, depende del firmware)
--   offline_alert_sent   → kiosko (aplica a cualquier tipo de dispositivo offline)
-- Si se cambia el set de encodings permitidos, modificar el CHECK en kiosko_zkteco.
```

## 17. Observabilidad

Métricas Micrometer (con dimension `tenant_id`, `plant_id`, `kiosko_id`):

```
adms.request.total{table, sn, plant_id}
adms.request.duration_ms{table, sn} (histogram)
adms.request.payload_size_bytes{table} (histogram)
adms.events.persisted{tenant_id, plant_id}
adms.events.duplicate{tenant_id, plant_id}
adms.events.orphan{tenant_id, kiosko_id}
adms.events.dead_letter{tenant_id, error_class}
adms.commands.enqueued{kiosko_id, command_type}
adms.commands.drained{kiosko_id}
adms.commands.acked{kiosko_id}
adms.commands.failed{kiosko_id}
adms.kioskos.online{tenant_id, plant_id}
adms.kioskos.offline{tenant_id, plant_id}
```

Logs estructurados (vía MDC):

```json
{
  "timestamp": "2026-04-30T14:32:01.234Z",
  "level": "INFO",
  "logger": "AttlogTableProcessor",
  "message": "Processed ATTLOG batch",
  "tenant_id": "...",
  "plant_id": "...",
  "kiosko_id": "...",
  "kiosko_serial": "K50PRO123ABC",
  "trace_id": "...",
  "events_persisted": 47,
  "events_duplicate": 2,
  "events_orphan": 1,
  "events_dead_letter": 0,
  "duration_ms": 87
}
```

## 18. Tests del módulo

| Tipo | Cubre | Tools |
|---|---|---|
| Unit | Parser, Translator, Visitor, Pipeline steps | JUnit 5 + Mockito |
| Integration | Controller con @WebMvcTest + GlobalExceptionHandler | Spring MVC Test |
| Integration | Repos + RLS + cross-tenant isolation | @DataJpaTest + Testcontainers |
| Integration | Pipeline end-to-end con DB real | @SpringBootTest + Testcontainers |
| Architecture | Adapter no importa otros adapters | ArchUnit |
| Contract | Format ADMS reproducible | Snapshot tests con golden files |

Ver [14-test-architecture.md](./14-test-architecture.md) para detalle.

## 19. Spike de validación W3-W4

Items obligatorios a validar antes de cerrar este diseño:

- [ ] **VALIDAR**: formato exacto del payload ATTLOG (capturar wireshark, samples reales).
- [ ] **VALIDAR**: formato exacto del payload OPERLOG.
- [ ] **VALIDAR**: encoding real (UTF-8 vs GBK) con nombre acentuado.
- [ ] **VALIDAR**: si firmware soporta `Realtime=1` o solo batch.
- [ ] **VALIDAR**: HTTPS soportado.
- [ ] **VALIDAR**: comportamiento ante timeout del backend (¿reintenta?).
- [ ] **VALIDAR**: latencia heartbeat real.
- [ ] **VALIDAR**: formato exacto de respuesta a `/getrequest` que el K50 acepta.
- [ ] **VALIDAR**: formato exacto del ACK que el device envía a `/devicecmd`.

Resultados se persisten en `docs/spikes/ADMS-K50Pro-resultados.md`. Si algún item falla, activar [ADR-007 Plan B Windows Bridge](./adrs/ADR-007-windows-bridge-plan-b.md).

## 20. Roadmap dentro del módulo

```
MVP (W4-W5):
  ✓ Endpoints /iclock/* básicos
  ✓ Strategy + Registry para tablas (solo ATTLOG)
  ✓ Parser ATTLOG
  ✓ Translator ADMS → Event
  ✓ Pipeline con steps básicos
  ✓ DeviceCommandService (queue + drain)
  ✓ DeviceCommandQueueListener (eventos básicos)
  ✓ Heartbeat + offline detection

W6-W11:
  ✓ Hardening, métricas, alarmas
  ✓ Observabilidad completa
  ✓ Tests cobertura > 85%

v1.1 (post-MVP):
  ☐ Procesador OPERLOG (alta de usuarios desde device)
  ☐ Endpoint /edata para upload de templates biométricas
  ☐ UpdateFingertmp command (sync server → device)
  ☐ Re-enrolamiento desde admin web

v2:
  ☐ Procesador ATTPHOTO (foto del kiosko)
  ☐ HikvisionSerializerVisitor (multi-vendor)
  ☐ TLS mutual auth con cert client
  ☐ WindowsBridgeSerializer (si pegó Plan B)
```

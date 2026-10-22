# Arquitectura de tests

## Principios

1. **Pirámide invertida es anti-patrón**. Muchos unit tests rápidos, menos integration, pocos e2e.
2. **Cada test prueba UNA cosa**. Asserts mínimos, nombres descriptivos.
3. **Tests son código de producción**. Reciben code review, refactor, mantienen cobertura.
4. **Tests rápidos son tests usados**. Suite unit < 30s, integration < 3min, e2e < 10min.
5. **Tests de arquitectura son contratos**. ArchUnit en CI rompe builds que violen reglas.
6. **Reproducibilidad**: cero dependencia de orden de ejecución, cero estado compartido.

## Pirámide

```
                     ┌──────────────────┐
                     │   E2E (10-20)    │  ← @SpringBootTest full + Testcontainers
                     │   minutos        │
                     └──────────────────┘
                ┌─────────────────────────────┐
                │  INTEGRATION (100-200)      │  ← @WebMvcTest, @DataJpaTest, 
                │  segundos                   │     Testcontainers selectivo
                └─────────────────────────────┘
        ┌─────────────────────────────────────────────┐
        │  UNIT (500-1000+)                            │  ← JUnit + Mockito
        │  milisegundos cada uno, segundos suite       │     SIN Spring context
        └─────────────────────────────────────────────┘
        ┌─────────────────────────────────────────────┐
        │  ARCHITECTURE (10-30)                        │  ← ArchUnit
        │  segundos suite                              │     Reglas como código
        └─────────────────────────────────────────────┘
```

## Estructura de paquetes de tests

```
src/test/java/com/assistance/
├── unit/                           ← @Tag("unit"), sin Spring, mocks
│   ├── domain/
│   │   ├── event/
│   │   │   ├── EventCaptureServiceImplTest
│   │   │   └── EventValidatorTest
│   │   ├── employee/
│   │   ├── policy/
│   │   └── calculation/
│   │       ├── HoursCalculatorTest
│   │       ├── OvertimeSplitterTest
│   │       └── PolicyApplierTest
│   └── adapter/
│       └── adms/
│           ├── parser/
│           │   ├── AdmsAttlogParserTest
│           │   └── AdmsOperlogParserTest
│           ├── command/
│           │   └── AdmsSerializerVisitorTest
│           ├── translator/
│           │   └── AdmsRecordTranslatorTest
│           └── pipeline/
│               └── ResolveEmployeeStepTest
│
├── integration/                    ← @Tag("integration"), Spring context selectivo
│   ├── controller/
│   │   ├── EmployeeControllerTest      ← @WebMvcTest (no DB, MockBean services)
│   │   ├── EventCaptureControllerTest
│   │   └── AdmsControllerTest
│   ├── persistence/                ← @DataJpaTest + Testcontainers Postgres
│   │   ├── EmployeeRepositoryTest
│   │   ├── EventRepositoryTest
│   │   └── RowLevelSecurityTest    ← test cross-tenant isolation
│   ├── advice/
│   │   ├── GlobalExceptionHandlerTest
│   │   └── BeanValidationTest
│   └── aspect/
│       ├── TenantScopedAspectTest
│       ├── IdempotencyAspectTest
│       └── AuthorizationAspectTest
│
├── e2e/                            ← @Tag("e2e"), full @SpringBootTest
│   ├── EventCaptureE2ETest         ← admin → kiosko web → cálculo
│   ├── PayPeriodCloseE2ETest       ← cierre completo + export CSV
│   └── PushAdapterE2ETest          ← simulación K50 Pro completa
│
└── architecture/                   ← @Tag("architecture"), ArchUnit
    ├── HexagonalArchitectureTest
    ├── ServiceLayerArchitectureTest
    ├── ControllerArchitectureTest
    ├── PackagingArchitectureTest
    └── DependencyArchitectureTest
```

## 1. Tests unit

**Sin Spring context. Sin DB. Sin nada.** Mocks de todas las dependencias. Latencia ms por test.

```java
class EventCaptureServiceImplTest {
    
    private EventRepository repo;
    private EmployeeDirectory employeeDirectory;
    private EventValidator validator;
    private ApplicationEventPublisher events;
    private MeterRegistry metrics;
    private EventCaptureService service;
    
    @BeforeEach
    void setUp() {
        repo = mock();
        employeeDirectory = mock();
        validator = mock();
        events = mock();
        metrics = new SimpleMeterRegistry();
        service = new EventCaptureServiceImpl(repo, employeeDirectory, validator, events, metrics);
    }
    
    @Test
    void capture_persiste_evento_y_publica_dominio_event_cuando_input_valido() {
        // Given
        var employee = anEmployee().build();
        var command = aCaptureCommand().withEmployeeNumber(employee.number()).build();
        when(employeeDirectory.findActive(any(), eq(employee.number()))).thenReturn(Optional.of(employee));
        when(validator.validate(any(), any())).thenReturn(List.of());
        when(repo.saveIfAbsent(any())).thenAnswer(inv -> SaveResult.persisted(inv.getArgument(0)));
        
        // When
        var result = service.capture(command);
        
        // Then
        assertThat(result.eventId()).isNotNull();
        assertThat(result.wasDuplicate()).isFalse();
        verify(events).publishEvent(any(EventCaptured.class));
        assertThat(metrics.counter("event.capture.success").count()).isEqualTo(1.0);
    }
    
    @Test
    void capture_lanza_exception_cuando_empleado_no_existe() {
        var command = aCaptureCommand().withEmployeeNumber(EmployeeNumber.of("EMP-999")).build();
        when(employeeDirectory.findActive(any(), any())).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.capture(command))
            .isInstanceOf(EmployeeNotFoundException.class)
            .hasMessageContaining("EMP-999");
    }
    
    @Test
    void capture_lanza_validation_exception_cuando_validator_falla() {
        var employee = anEmployee().build();
        var command = aCaptureCommand().withEmployeeNumber(employee.number()).build();
        when(employeeDirectory.findActive(any(), any())).thenReturn(Optional.of(employee));
        when(validator.validate(any(), any())).thenReturn(List.of(
            new ValidationError("event_time", "OUT_OF_WINDOW", "Outside shift window")
        ));
        
        assertThatThrownBy(() -> service.capture(command))
            .isInstanceOf(EventValidationException.class);
    }
}
```

Test data builders (Lombok `@Builder` o manual):

```java
public class EmployeeFixtures {
    public static Employee.EmployeeBuilder anEmployee() {
        return Employee.builder()
            .id(EmployeeId.generate())
            .tenantId(TenantId.fromString("00000000-0000-0000-0000-000000000001"))
            .number(EmployeeNumber.of("EMP-001"))
            .fullName(FullName.of("Test User"))
            .status(EmployeeStatus.ACTIVE);
    }
}
```

## 2. Tests de integración: @WebMvcTest

**Para controllers + advice + filters + bean validation. Sin DB.** Spring context mínimo.

```java
@WebMvcTest(EmployeeController.class)
@Import({GlobalExceptionHandler.class, ProblemDetailFactory.class})
class EmployeeControllerTest {
    
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    
    @MockBean private EmployeeService employeeService;
    @MockBean private EmployeeMapper employeeMapper;
    @MockBean private UserContext userContext;
    
    @BeforeEach
    void mockAuth() {
        when(userContext.currentUser()).thenReturn(
            CurrentUser.builder()
                .id(UUID.randomUUID())
                .role(Role.TENANT_ADMIN)
                .tenantId(UUID.randomUUID())
                .build()
        );
    }
    
    @Test
    void post_employees_returns_201_with_dto_when_valid() throws Exception {
        var request = new CreateEmployeeRequest(
            "EMP-001", "Juan Pérez", "PEPJ900101HDFRRR01", 
            "PEPJ900101A1B", "12345678901", UUID.randomUUID(), null, 
            LocalDate.of(2026, 1, 15), false
        );
        
        when(employeeService.create(any())).thenReturn(anEmployee().build());
        when(employeeMapper.toDto(any())).thenReturn(new EmployeeDTO(...));
        
        mockMvc.perform(post("/api/v1/employees")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.employee_number").value("EMP-001"));
    }
    
    @Test
    void post_employees_returns_422_with_problem_detail_when_validation_fails() throws Exception {
        var request = new CreateEmployeeRequest(
            "", "", "INVALID-CURP", null, null, null, null, null, false
        );
        
        mockMvc.perform(post("/api/v1/employees")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.trace_id").exists());
    }
    
    @Test
    void post_employees_returns_404_when_service_throws_NotFound() throws Exception {
        when(employeeService.create(any())).thenThrow(
            new PlantNotFoundException(PlantId.fromString("...")));
        
        mockMvc.perform(post("/api/v1/employees")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestJson()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }
    
    @Test
    void post_employees_returns_403_when_role_insufficient() throws Exception {
        when(userContext.currentUser()).thenReturn(
            CurrentUser.builder().role(Role.SUPERVISOR).build()
        );
        
        mockMvc.perform(post("/api/v1/employees")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestJson()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }
}
```

## 3. Tests de integración: @DataJpaTest + Testcontainers

**Para repositorios + RLS + queries reales. Postgres real en container.**

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmployeeRepositoryConfig.class, JpaAuditingConfig.class})
class EmployeeRepositoryTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");
    
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    
    @Autowired private EmployeeRepository repo;
    @Autowired private EntityManager em;
    
    @Test
    void rls_filters_by_tenant_id_when_session_var_set() {
        // Given: 2 empleados en tenants distintos
        var tenant1 = UUID.randomUUID();
        var tenant2 = UUID.randomUUID();
        var emp1 = persistEmployeeForTenant(tenant1);
        var emp2 = persistEmployeeForTenant(tenant2);
        em.flush();
        em.clear();
        
        // When: SET app.tenant_id = tenant1
        setTenantContext(tenant1);
        
        // Then: solo veo emp1
        var found = repo.findAll();
        assertThat(found).extracting(EmployeeEntity::getId)
            .containsExactly(emp1.getId())
            .doesNotContain(emp2.getId());
    }
    
    @Test
    void rls_blocks_insert_with_different_tenant_id() {
        var tenant1 = UUID.randomUUID();
        var tenant2 = UUID.randomUUID();
        setTenantContext(tenant1);
        
        var entity = anEmployeeEntity().tenantId(tenant2).build();
        
        assertThatThrownBy(() -> {
            repo.save(entity);
            em.flush();
        }).isInstanceOf(JpaSystemException.class)
          .hasRootCauseMessage("new row violates row-level security policy");
    }
    
    private void setTenantContext(UUID tenantId) {
        em.unwrap(Session.class).doWork(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            }
        });
    }
}
```

```java
@DataJpaTest
@Testcontainers
class EmployeeSpecificationsTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    
    @Autowired private EmployeeRepository repo;
    
    @Test
    void hasStatus_filters_correctly() {
        persistEmployees(
            anEmployeeEntity().status(ACTIVE).build(),
            anEmployeeEntity().status(TERMINATED).build(),
            anEmployeeEntity().status(ACTIVE).build()
        );
        
        var result = repo.findAll(EmployeeSpecifications.hasStatus(ACTIVE));
        
        assertThat(result).hasSize(2);
    }
    
    @Test
    void composite_specification_combines_filters_with_and() {
        var plantA = UUID.randomUUID();
        var plantB = UUID.randomUUID();
        persistEmployees(
            anEmployeeEntity().plantId(plantA).status(ACTIVE).fullName("Juan").build(),
            anEmployeeEntity().plantId(plantA).status(TERMINATED).fullName("Juan").build(),
            anEmployeeEntity().plantId(plantB).status(ACTIVE).fullName("Juan").build()
        );
        
        var spec = where(EmployeeSpecifications.belongsToPlant(plantA))
            .and(EmployeeSpecifications.hasStatus(ACTIVE))
            .and(EmployeeSpecifications.nameContains("Juan"));
        
        var result = repo.findAll(spec);
        assertThat(result).hasSize(1);
    }
}
```

## 4. Tests de aspects (AOP)

```java
@SpringBootTest(classes = TenantScopedAspectTestConfig.class)
class TenantScopedAspectTest {
    
    @Autowired private SampleService sampleService;
    @Autowired private TenantContext tenantContext;
    @MockBean private EntityManager em;
    
    @Test
    void aspect_sets_app_tenant_id_in_postgres_session() {
        var tenantId = UUID.randomUUID();
        tenantContext.set(tenantId, null);
        
        sampleService.doSomething();
        
        verify(em.unwrap(Session.class)).doWork(argThat(work -> {
            var conn = mock(Connection.class);
            work.execute(conn);
            verify(conn).createStatement().execute(
                argThat((String s) -> s.contains("SET LOCAL app.tenant_id = '" + tenantId + "'"))
            );
            return true;
        }));
    }
    
    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TenantScopedAspectTestConfig {
        @Bean SampleService sampleService() { return new SampleServiceImpl(); }
        @Bean TenantContext tenantContext() { return new TenantContext(); }
        @Bean TenantScopedAspect tenantScopedAspect(TenantContext ctx, EntityManager em) {
            return new TenantScopedAspect(ctx, em);
        }
    }
    
    @Service
    static class SampleServiceImpl implements SampleService {
        @TenantScoped
        public void doSomething() {}
    }
}
```

## 5. Tests E2E con @SpringBootTest

**Pocos pero completos.** Validan flujos críticos end-to-end.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@AutoConfigureMockMvc
class EventCaptureE2ETest {
    
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static LocalStackContainer localstack = new LocalStackContainer(...);
    
    @Autowired private MockMvc mockMvc;
    @Autowired private TestSeeder seeder;
    
    @Test
    void event_captured_via_admms_persists_and_appears_in_attendance() throws Exception {
        // Given
        var tenant = seeder.tenant().named("Empresa Piloto").create();
        var plant = seeder.plant(tenant).named("Planta Norte").create();
        var kiosko = seeder.kiosko(plant).serial("K50PRO-TEST-001").create();
        var employee = seeder.employee(tenant, plant).number("EMP-001").create();
        
        // When: simulate ADMS POST
        var admsBody = "EMP-001\t2026-04-30 06:00:00\t0\t1\t0\t0";
        
        mockMvc.perform(post("/iclock/cdata")
                .param("SN", "K50PRO-TEST-001")
                .param("table", "ATTLOG")
                .param("Stamp", "0")
                .contentType(MediaType.TEXT_PLAIN)
                .content(admsBody))
            .andExpect(status().isOk())
            .andExpect(content().string(startsWith("OK:")));
        
        // Then: event appears in admin query
        mockMvc.perform(get("/api/v1/events")
                .header("Authorization", seeder.adminTokenFor(tenant))
                .param("employee_id", employee.id().value().toString())
                .param("from", "2026-04-30T00:00:00Z")
                .param("to", "2026-04-30T23:59:59Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].type").value("CHECK_IN"));
        
        // And: dashboard shows kiosko online
        mockMvc.perform(get("/api/v1/plants/{id}/dashboard", plant.id().value())
                .header("Authorization", seeder.adminTokenFor(tenant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kioskos_online").value(1));
    }
}
```

## 6. Tests de arquitectura con ArchUnit

**Reglas como código que rompen build en CI**.

```java
@AnalyzeClasses(packages = "com.assistance", 
                importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {
    
    @ArchTest
    static final ArchRule domain_no_depende_de_adapter =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("Hexagonal: domain define ports, adapter los satisface");
    
    @ArchTest
    static final ArchRule domain_no_usa_spring_web =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..",
                "jakarta.servlet..")
            .because("Domain debe ser HTTP-agnostic");
    
    @ArchTest
    static final ArchRule domain_no_usa_jpa_entities_directly =
        noClasses().that().resideInAPackage("..domain..service..")
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .because("Services usan domain models, no entities. Repos hacen el bridge");
    
    @ArchTest
    static final ArchRule adapter_adms_no_depende_de_otros_adapters =
        noClasses().that().resideInAPackage("..adapter.adms..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.web..");
    
    @ArchTest
    static final ArchRule translator_unica_clase_que_cruza_adapter_domain =
        classes().that().resideInAPackage("..adapter.adms..")
            .and().dependOnClassesThat().resideInAPackage("..domain.event..")
            .should().haveSimpleNameEndingWith("Translator")
            .orShould().haveSimpleNameEndingWith("Mapper")
            .because("Solo translators/mappers cruzan la frontera adapter↔domain");
}
```

```java
@AnalyzeClasses(packages = "com.assistance")
class ServiceLayerArchitectureTest {
    
    @ArchTest
    static final ArchRule services_publicos_son_interfaces =
        classes().that().areAnnotatedWith(Service.class)
            .should().notBePublic()
            .orShould().beInterfaces()
            .because("Services públicos se programan contra interfaz (ADR-006)");
    
    @ArchTest
    static final ArchRule service_impl_implementan_interfaz_correspondiente =
        classes().that().haveSimpleNameEndingWith("ServiceImpl")
            .should().implement(JavaClass.Predicates.simpleNameEndingWith("Service"))
            .because("Convención de naming: FooServiceImpl implements FooService");
    
    @ArchTest
    static final ArchRule services_son_transactional =
        classes().that().haveSimpleNameEndingWith("ServiceImpl")
            .should().beAnnotatedWith(Transactional.class)
            .orShould().bePackagePrivate()
            .because("Service Impl define transactional boundaries");
    
    @ArchTest
    static final ArchRule no_field_injection =
        noFields().should().beAnnotatedWith(Autowired.class)
            .because("Constructor injection con final fields (ADR-006)");
    
    @ArchTest
    static final ArchRule no_field_injection_inject =
        noFields().should().beAnnotatedWith(Inject.class);
}
```

```java
@AnalyzeClasses(packages = "com.assistance")
class ControllerArchitectureTest {
    
    @ArchTest
    static final ArchRule controllers_no_lanzan_excepciones_genericas =
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("..controller..")
            .should().declareThrowableOfType(Exception.class)
            .because("Excepciones del dominio se manejan en @RestControllerAdvice");
    
    @ArchTest
    static final ArchRule controllers_no_acceden_directamente_a_repos =
        noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("Controllers usan services, no repos directamente");
    
    @ArchTest
    static final ArchRule controllers_no_tienen_logica_de_negocio =
        classes().that().resideInAPackage("..controller..")
            .should().haveSimpleNameEndingWith("Controller");
    
    @ArchTest
    static final ArchRule controllers_returnan_responseEntity_o_dto =
        methods().that().areDeclaredInClassesThat().resideInAPackage("..controller..")
            .and().areAnnotatedWith(GetMapping.class)
            .or().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class)
            .or().areAnnotatedWith(PatchMapping.class)
            .should().haveRawReturnType(JavaClass.Predicates.assignableTo(ResponseEntity.class))
            .orShould().haveRawReturnType(JavaClass.Predicates.simpleNameEndingWith("DTO"))
            .orShould().haveRawReturnType(JavaClass.Predicates.simpleNameEndingWith("Response"));
}
```

```java
@AnalyzeClasses(packages = "com.assistance")
class PackagingArchitectureTest {
    
    @ArchTest
    static final ArchRule layered_architecture_respected = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Controller").definedBy("..controller..")
        .layer("Service").definedBy("..service..")
        .layer("Repository").definedBy("..repository..")
        .layer("Domain").definedBy("..domain..")
        
        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");
    
    @ArchTest
    static final ArchRule no_cycles_between_packages =
        slices().matching("com.assistance.(*)..").should().beFreeOfCycles();
}
```

## 7. Test data builders y fixtures

```java
public class TestFixtures {
    
    public static class EmployeeFixture {
        public static Employee.EmployeeBuilder anEmployee() {
            return Employee.builder()
                .id(EmployeeId.generate())
                .tenantId(TenantId.fromString("00000000-0000-0000-0000-000000000001"))
                .number(EmployeeNumber.of("EMP-" + ThreadLocalRandom.current().nextInt(1000, 9999)))
                .fullName(FullName.of("Test User"))
                .status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2026, 1, 15));
        }
    }
    
    public static class CaptureCommandFixture {
        public static CaptureCommand.CaptureCommandBuilder aCaptureCommand() {
            return CaptureCommand.builder()
                .id(EventId.generate())
                .tenantId(TenantId.fromString("..."))
                .plantId(PlantId.fromString("..."))
                .kioskoId(KioskoId.fromString("..."))
                .employeeId(EmployeeId.generate())
                .eventType(EventType.CHECK_IN)
                .captureMethod(CaptureMethod.NFC)
                .deviceTime(Instant.parse("2026-04-30T06:00:00Z"))
                .deviceOffsetMs(0)
                .eventTime(Instant.parse("2026-04-30T06:00:00Z"));
        }
    }
}
```

## 8. Configuración Maven/Gradle

### Surefire/Failsafe + tags

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <groups>unit | architecture</groups>  <!-- solo unit + arch en build normal -->
    </configuration>
</plugin>

<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <groups>integration | e2e</groups>  <!-- corre en CI integration profile -->
    </configuration>
</plugin>
```

### CI pipeline

```yaml
test-unit-and-arch:
  script: mvn test -Dgroups="unit | architecture"
  duration: < 1 minuto

test-integration:
  script: mvn verify -Dgroups="integration"
  duration: < 5 minutos
  
test-e2e:
  script: mvn verify -Dgroups="e2e"
  duration: < 10 minutos
  only: main, develop branches
```

## 9. Cobertura

| Capa | Cobertura mínima | Tipo de tests dominante |
|---|---|---|
| Domain (services, value objects, calculation) | **≥ 90%** | Unit |
| Adapters (parsers, translators, controllers) | ≥ 85% | Unit + WebMvc |
| Repositories | ≥ 80% | DataJpaTest + Testcontainers |
| Aspects | ≥ 95% | Unit + Integration |
| AOP cross-cutting | 100% | Specific aspect tests |
| Configuration | ≥ 70% | Smoke tests al arranque |
| **Global** | **≥ 80%** | — |

JaCoCo en CI con threshold automático. Build rompe si baja.

## 10. Mocking strategy

**Qué mockear y qué no**:

```
Mockear (en unit tests):
  ✓ Repositorios (interfaz)
  ✓ Servicios externos (Cognito, S3, SES, KMS)
  ✓ Domain events publisher
  ✓ Otros services (cuando se testea uno aislado)
  
NO mockear:
  ✗ Value objects (son inmutables, son sus propios fixtures)
  ✗ Records de DTOs/Commands
  ✗ Mappers MapStruct (son determinísticos, mejor usar el real)
  ✗ Validators (lo mismo)
  
En integration tests:
  ✓ Postgres real con Testcontainers
  ✓ S3/Cognito con LocalStack
  ✓ Solo mockean servicios externos NO disponibles
```

## 11. Tests de performance (selectivos, no en cada PR)

```java
@Tag("performance")
@SpringBootTest
class EventCaptureLoadTest {
    
    @Test
    @Disabled("Run manually before pre-prod deploy")
    void can_handle_120_events_per_minute_per_kiosko() {
        // gatling o k6 simulando carga
    }
}
```

## 12. Mutation testing (opcional, alto valor)

Pitest para validar que los tests **realmente** prueban algo:

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <configuration>
        <targetClasses><param>com.assistance.domain.calculation.*</param></targetClasses>
        <mutationThreshold>75</mutationThreshold>
    </configuration>
</plugin>
```

Aplicado **solo** al motor de cálculo de horas (es la pieza más crítica). El resto del código no justifica el tiempo de pitest.

## 13. Smoke tests al arranque

```java
@Component
@RequiredArgsConstructor
class StartupSmokeRunner implements ApplicationRunner {
    
    private final DataSource dataSource;
    private final CognitoClient cognito;
    private final HealthIndicator kioskoFleetHealth;
    
    @Override
    public void run(ApplicationArguments args) {
        // Connection a DB
        try (var conn = dataSource.getConnection()) {
            assertThat(conn.isValid(5)).isTrue();
        }
        
        // Cognito alcanzable
        cognito.healthCheck();
        
        // Health indicator funcional
        var health = kioskoFleetHealth.health();
        log.info("Startup smoke OK. Health: {}", health);
    }
}
```

## 14. Anti-patrones prohibidos

```
❌ Tests con orden dependiente (@TestMethodOrder con casi cualquier estrategia)
❌ Tests que comparten estado mutable
❌ Tests que dependen de zona horaria del CI server
❌ Tests con Thread.sleep() en lugar de Awaitility
❌ Tests con dependencia de servicios externos reales
❌ Tests que solo validan que no haya excepción (sin asserts reales)
❌ Tests con asserts en orden vs contains (cuando el orden no es relevante)
❌ Tests con Mockito.when para 50 métodos del mismo mock (refactor service)
❌ Comentarios "// TODO: agregar test" sin issue tracking
```

## Resumen ejecutable

```
make test-unit          # < 1 min, cero side effects
make test-integration   # < 5 min, Testcontainers
make test-arch          # < 30s, ArchUnit
make test-e2e           # < 10 min, full Spring + containers
make test-all           # todo
make test-coverage      # JaCoCo report
```

CI corre `test-unit + test-arch` en cada PR. `test-integration + test-e2e` en merge a main. `test-coverage` en cada PR con threshold 80%.

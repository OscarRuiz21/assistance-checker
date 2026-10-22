# ADR-006: Arquitectura de aplicación (services, error handling, AOP, sealed types, hexagonal)

**Estado**: Aceptado
**Fecha**: 2026-04-30
**Decisores**: Tech Lead
**Relacionado**: [ADR-005](./ADR-005-strategy-pattern-extensibilidad.md), [13-patrones-arquitectonicos.md](../13-patrones-arquitectonicos.md), [12-push-adapter.md](../12-push-adapter.md)

## Contexto

Durante el diseño detallado del Push Adapter aparecieron `switch` statements y omisiones estructurales que un equipo Java/Spring Boot enterprise no debe permitir:

- Dispatch lineal sobre strings (`switch (table)`, `switch (commandType)`).
- Falta de separación entre interfaz de Service e implementación.
- Manejo de errores disperso en controllers en lugar de centralizado.
- Cross-cutting concerns (tenancy, idempotencia, auth, audit) implementados ad-hoc en cada service.

Estos son code smells documentados que erosionan extensibilidad, testabilidad y mantenibilidad.

## Decisión

Adoptar formalmente el siguiente conjunto de patrones como **estándar arquitectónico no-negociable** del proyecto:

### Estructura

1. **Hexagonal architecture / Ports & Adapters**. Domain define ports; adapters los satisfacen. Domain no importa adapter ni Spring Web.
2. **Anti-Corruption Layer**. Vocabulario de protocolos externos (ADMS, ZKTeco, etc.) no se filtra al core. Translators son la única clase puente.
3. **Service como interfaz + Impl separada**. Todo `@Service` público se programa contra interfaz. Esto habilita decoración, AOP proxying, y mocks simples.

### Error handling

4. **`@RestControllerAdvice` global**. Cero try/catch en controllers. Excepciones del dominio se traducen a HTTP en un único punto.
5. **Jerarquía sealed de excepciones**. `DomainException` con sub-tipos `NotFoundException`, `BusinessRuleException`, `ConflictException`, `ValidationException`, `ForbiddenException`. Cada excepción concreta lleva `ErrorCode` machine-readable y contexto JSON.
6. **Problem+JSON RFC 7807**. Formato uniforme de respuesta de error con `code`, `trace_id`, `instance`, contexto.

### Cross-cutting concerns vía AOP

7. **Custom annotations + Aspect**. Cada preocupación transversal es una anotación:
   - `@TenantScoped` — setea `app.tenant_id` en sesión Postgres.
   - `@Idempotent` — valida `Idempotency-Key` y cachea respuesta.
   - `@RequiresRole` / `@RequiresPlantAccess` — autorización declarativa.
   - `@AuditedAction` — audit log automático.
   - `@RateLimited` — bucket por clave configurable.

### Type safety

8. **Sealed types + pattern matching (Java 21)** para jerarquías controladas con exhaustividad del compilador.
9. **Strategy + Spring Registry** (`Map<String, T>` injection) para extensiones tipo plugin sin modificar código.
10. **Visitor pattern** para múltiples comportamientos sobre jerarquías sealed (commands, events, etc.).
11. **Value Objects** en lugar de primitivos sueltos (`EmployeeNumber`, `TenantId`, `EventId`, `EmailAddress`).
12. **Records inmutables** para DTOs y commands.

### Infraestructura

13. **MapStruct** para mappers DTO ↔ Domain ↔ Entity con `unmappedTargetPolicy = ERROR`.
14. **Bean Validation Jakarta** + custom validators para reglas de input.
15. **Spring Data JPA Auditing** para `created_at`/`updated_by`.
16. **`@ConfigurationProperties` tipados y validados** con `@Validated`.
17. **`@ConditionalOnProperty` y `@Profile`** para activar features (Plan B Windows, etc.).
18. **Resilience4j** para llamadas externas (`@Retry`, `@CircuitBreaker`, `@TimeLimiter`).
19. **Caffeine + `@Cacheable`** para caching declarativo con métricas.
20. **MDC structured logging** con tenant_id, trace_id, request_id, user_id automáticos.
21. **Specifications de Spring Data** para queries dinámicas type-safe.

### Comunicación entre bounded contexts

22. **Domain events** vía `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`. Sin acoplamiento directo entre módulos.

### Inyección

23. **Constructor injection + final fields**. Cero `@Autowired` en field. Lombok `@RequiredArgsConstructor`.

### Verificación arquitectónica

24. **ArchUnit como contratos ejecutables en CI**. Reglas de:
   - Domain no importa adapter ni Spring Web.
   - Services públicos son interfaces.
   - Service Impl implementa interfaz correspondiente.
   - Controllers no usan try/catch.
   - Cero field injection.
   - Cross-package dependencies controladas.

## Razonamiento

### Por qué "todo o nada"

Adoptar mitad de estos patrones es peor que adoptar todos: el código mixto genera confusión sobre qué convención aplica dónde. La consistencia es la que da productividad de equipo.

### Por qué Java 21 features (sealed, records, pattern matching)

- **Exhaustividad del compilador** elimina bugs por casos olvidados.
- **Records inmutables** eliminan boilerplate y bugs de mutación accidental.
- **Pattern matching** reemplaza if/else cascadas con código declarativo.

Stack ya es Java 21, no hay razón para no usarlas.

### Por qué AOP custom en lugar de filters/interceptors

- AOP genera proxies que se aplican uniformemente a cualquier `@Service` o `@Controller`.
- Anotaciones custom son **declarativas**: `@RequiresRole(Role.RH)` se lee al instante.
- Los aspects son testeables en aislamiento.
- Filters/interceptors son menos granulares y más invasivos en código.

### Por qué Strategy + Registry encima de switch

- Agregar variante = nueva `@Component`, **cero modificación** en el dispatcher.
- Cada variante es testeable en aislamiento.
- Spring DI hace el wire-up automáticamente.
- Open/Closed satisfecho de manera mecánica.

### Por qué hexagonal con anti-corruption layer

- El producto ya tiene **3 vías de captura** (ADMS, web kiosko, mobile futuro). Sin hexagonal, el dominio se contaminaría con vocabulario de cada una.
- El **Plan B Windows Bridge** es un cuarto adapter que se conecta al mismo port. Hexagonal lo hace trivial.
- Cliente regulado en v2/v3 podría exigir adapter custom (ej. SAP integration); hexagonal lo permite.

### Por qué centralized error handling

- Controllers se reducen a ~10 líneas: parse, validate, delegate, return.
- Formato uniforme de respuesta de error es contractual con clientes (Web, mobile, SDK gen).
- Métricas y logs de errores en un solo lugar.
- Soporte puede correlacionar errores por `trace_id` y `code` sin parsear stacktraces.

### Por qué services como interfaz

Múltiples razones, todas combinadas:

1. **Spring AOP requiere proxy**. Para `@Transactional`, `@Cacheable`, `@TenantScoped` y nuestros aspects custom funcionar limpiamente, una interfaz hace el proxy directo (JDK dynamic proxy). Sin interfaz, Spring usa CGLib que tiene limitaciones (no funciona con `final` classes/methods, métodos `private` no son interceptados).
2. **Tests unit limpios**. Mocks de la interfaz, no de la clase. Mockito mock de interface es más rápido y claro.
3. **Decoración futura**. `@Primary` con interfaz permite envolver con caching, métricas, etc., sin tocar callers.
4. **Documentación implícita**. La interfaz es la API pública del bounded context. La impl es detalle.

## Consecuencias

### Positivas

- Extensibilidad mecánica: agregar capacidad = nueva clase, no modificar existente.
- Tests aislados, rápidos, mantenibles.
- Type-safety en caminos críticos (sealed types, records, value objects).
- Observabilidad consistente (MDC + métricas + traces).
- Arquitectura verificable (ArchUnit en CI rompe builds que violan).
- Onboarding más rápido: una vez aprendidos los patrones, son uniformes en todo el código.
- Refactor a microservicios futuro (si lo justifica el crecimiento) es trivial: cada bounded context ya está aislado.

### Negativas

- **Sobrecabeza inicial**: más archivos por feature (interfaz + impl + DTOs + mapper + tests). ~30% más LOC vs estilo "monolito relajado".
- **Curva de aprendizaje**: dev nuevo en Spring puede tardar 1-2 días en navegar la estructura.
- **Resistencia psicológica**: equipo acostumbrado a `if (type==X)` debe adaptarse.
- **Tests ArchUnit a mantener**: agregar/quitar paquetes requiere actualizar reglas.

### Compromisos pragmáticos

- Para módulos **pequeños y triviales** (ej. health check), service interface + impl es overkill. Permitido omitir si el módulo tiene < 50 LOC totales.
- AOP custom annotation no se justifica si solo se usa en 1 lugar. Inline el aspect logic ahí.
- Sealed types para jerarquías de < 3 elementos puede ser overkill. Usar enum.

## Implementación

### Sprint 1 (W1-W2): cimientos

- ✓ Estructura de paquetes hexagonal.
- ✓ `GlobalExceptionHandler` con `ProblemDetailFactory`.
- ✓ Jerarquía base de `DomainException` y subtipos.
- ✓ `@TenantScoped` aspect + `TenantContext`.
- ✓ MDC filter + Logback JSON.
- ✓ Tests ArchUnit iniciales (5 reglas críticas).

### Sprint 2 (W3-W4): paterns en uso

- ✓ Primer service con interfaz + impl + tests.
- ✓ Primer controller con `@RestControllerAdvice` ejercitado.
- ✓ Primer mapper MapStruct.
- ✓ `@AuditedAction` + audit triggers en DB.
- ✓ Primera Specification para query dinámica.

### Sprint 3-12: aplicación uniforme

Cada feature nueva sigue los patrones. Code review obligatoria valida:

- [ ] Service tiene interfaz pública + Impl @Service interno
- [ ] Controller no tiene try/catch
- [ ] Errores son DomainException, no genéricos
- [ ] Mappers son MapStruct, no manuales
- [ ] DTOs son records con Bean Validation
- [ ] AOP custom annotations donde aplique
- [ ] Tests unit + integration + ArchUnit

## Métricas de éxito

| Métrica | Target |
|---|---|
| Reglas ArchUnit pasando | 100% en CI, todo deploy |
| Cobertura tests unit + integration | ≥ 80% |
| Tiempo de onboarding nuevo dev (a primera PR) | ≤ 3 días |
| Bugs reportados por código mal estructurado | < 1 / mes |
| Refactor para agregar nuevo exporter (medido en MVP) | ≤ 1 día |

## Alternativas rechazadas

### "Estilo Spring Boot relajado" (sin interfaces, controllers gordos)

Razón rechazada: equipo de 3 ingenieros y 12 semanas ya es ajustado. Cualquier deuda técnica acumula intereses rápido. Estos patrones son inversión que paga en v1.1 y v2.

### Microservicios desde MVP

Razón rechazada: 3 ingenieros, 12 semanas, 2 tenants. Microservicios requieren operación distribuida (logging cross-service, distributed tracing, contract testing) que no podemos absorber. **Monolito modular** con bounded contexts da los mismos beneficios sin el costo operacional.

### Dependency injection manual (sin Spring)

Razón rechazada: la estandarización del equipo es Java/Spring. Cambiar a Guice o manual aumenta la curva de aprendizaje sin beneficio claro.

### Reactive (WebFlux)

Razón rechazada: el caso de uso (CRUD + cálculo) no se beneficia significativamente de reactive. Sí agrega complejidad de debugging y testing. Si en v3 hay caso (streaming de eventos), evaluar.

## Re-evaluar cuando

- Llegamos a 50+ tenants → reevaluar si hexagonal con monolito sigue siendo óptimo.
- Otro equipo (frontend, mobile) requiere cambios en el modelo de error → ajustar `ProblemDetailFactory` colaborativamente.
- Más de 3 protocolos de captura activos → evaluar si vale la pena extraer un microservicio para capture.
- Performance issues por proxying AOP → optimizar selectivamente, no abandonar el patrón.

## Referencias

- [Hexagonal Architecture — Alistair Cockburn](https://alistair.cockburn.us/hexagonal-architecture/)
- [Domain-Driven Design — Eric Evans](https://www.domainlanguage.com/ddd/)
- [Clean Architecture — Robert C. Martin](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [SOLID Principles](https://en.wikipedia.org/wiki/SOLID)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [RFC 7807: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807)

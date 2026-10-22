# Visión, personas y glosario

## Visión del producto

Construir el **sistema de control de asistencia más confiable y compliance-ready** para plantas industriales en México, con un modelo SaaS multi-tenant que permita servir a múltiples empresas sin compromisos de aislamiento, escalabilidad o personalización por planta.

**No** competimos con sistemas de nómina (CONTPAQi, Aspel NOI, SAP HR). Nos integramos con ellos como **fuente de verdad de horas trabajadas**.

## Personas (roles del sistema)

### Obrero
- **Perfil**: trabajador operativo en planta. No tiene cuenta de usuario.
- **Interacción**: marca asistencia con NFC, PIN o huella en kiosko de planta.
- **Necesidad**: confirmación rápida y clara de que su checada se registró (< 3 segundos).

### Supervisor
- **Perfil**: jefe de turno o de planta. Cuenta de usuario con acceso restringido a sus plantas.
- **Interacción**: web app (responsive en móvil).
- **Tareas**: revisar checadas del día, registrar correcciones, resolver review queue, modo "rescate" cuando un kiosko falla.

### RH (Recursos Humanos)
- **Perfil**: administrador de empleados y nómina. Cuenta de usuario con acceso a todas las plantas del tenant.
- **Interacción**: web app principal.
- **Tareas**: alta/baja de empleados, asignación de turnos, cierre de periodos de nómina, descarga de CSV para nómina.

### Tenant Admin
- **Perfil**: dueño/administrador de la empresa cliente. Configura plantas, perfiles de política, kioskos.
- **Interacción**: web app.
- **Tareas**: configuración inicial del tenant, gestión de usuarios internos, asignación de perfiles de política a plantas.

### Super Admin
- **Perfil**: equipo del SaaS (nosotros). No pertenece a ningún tenant.
- **Interacción**: web app con vista global.
- **Tareas**: onboarding de nuevos tenants, soporte transversal, gestión de perfiles de política globales, monitoreo.

## Glosario

### Conceptos del producto

| Término | Definición |
|---|---|
| **Tenant** | Empresa cliente del SaaS. Aislamiento total entre tenants. |
| **Planta** | Ubicación física de operación dentro de un tenant. Cada planta tiene su propia configuración, kioskos y empleados (con posible solapamiento). |
| **Kiosko** | Dispositivo físico de captura en planta (en MVP: ZKTeco K50 Pro). En modo web kiosko, un navegador en pantalla dedicada también cuenta. |
| **Empleado** | Persona registrada en el tenant. Tiene número, datos personales (CURP, RFC, NSS), turno, planta default, métodos de captura asignados. |
| **Evento (event)** | Registro inmutable de una acción de asistencia: entrada, salida, salida-comida, entrada-comida. |
| **Checada** | Sinónimo coloquial de evento (uso mexicano). |
| **Política (policy)** | Conjunto de reglas configurables por planta: tolerancias, métodos, manejo de comida, tope de horas extra, etc. |
| **Perfil de política** | Plantilla preconfigurada de política. En MVP existen 2: "Industrial estándar" y "Administrativo". |
| **Periodo de pago (pay period)** | Intervalo de tiempo cerrado para cálculo de nómina. Tipos: semanal, catorcenal, quincenal, mensual. |
| **Día contable (business date)** | Fecha lógica a la que se asigna un turno, distinta de la fecha física en turnos que cruzan medianoche. |
| **Review queue** | Cola de casos que requieren intervención de supervisor: conflictos multi-kiosko, checadas faltantes, etc. |
| **Modo rescate** | Funcionalidad que permite al supervisor registrar checadas manualmente desde su app cuando un kiosko falla. |

### Conceptos legales (LFT)

| Término | Definición |
|---|---|
| **LFT** | Ley Federal del Trabajo (México). Marco legal que define jornadas, horas extra, descansos. |
| **Jornada diurna** | 6:00 a 20:00. Máximo 8 horas/día. |
| **Jornada nocturna** | 20:00 a 6:00. Máximo 7 horas/día. |
| **Jornada mixta** | Combinación con < 3.5 h nocturnas. Máximo 7.5 horas/día. |
| **Horas extra (HE)** | Tiempo arriba de la jornada normal. Máximo legal: 3 h/día, 9 h/semana. |
| **HE 2x** | Primeras 9 h extra/semana. Pago doble. |
| **HE 3x** | A partir de la décima h extra/semana, o cualquier h fuera del tope diario. Pago triple. |
| **Prima dominical** | 25% adicional sobre salario por trabajar domingo (cuando es día de descanso). |
| **Día de descanso obligatorio** | Festivo oficial Art. 74 LFT (10 fechas/año). Si se trabaja: pago triple. |
| **Día de descanso semanal** | Día libre semanal pagado. Si se trabaja: pago triple. |
| **Tolerancia de retardo** | Minutos de gracia después de la hora de entrada antes de marcar retardo. Configurable por planta. |
| **Aviso de privacidad** | Documento que el empleado firma autorizando uso de sus datos. Obligatorio LFPDPPP. |
| **Derechos ARCO** | Acceso, Rectificación, Cancelación, Oposición — derechos del titular de datos personales. |

### Conceptos técnicos

| Término | Definición |
|---|---|
| **ADMS** | Anti-passback Data Management System. Protocolo HTTP propietario de ZKTeco para que dispositivos pusheen eventos a un servidor cloud. |
| **Push protocol** | Modelo de comunicación donde el dispositivo (no el backend) inicia la conexión y envía datos. Esencial para SaaS multi-tenant. |
| **Pull SDK / Standalone SDK** | SDK Windows COM/ActiveX de ZKTeco para conectar al dispositivo desde el backend. **No usado en este proyecto**, ver [ADR-003](./adrs/ADR-003-zkteco-adms-no-sdk.md). |
| **RLS (Row-Level Security)** | Característica de PostgreSQL que permite filtrar filas automáticamente por una condición (en este caso `tenant_id`). |
| **Tenant isolation** | Garantía de que un tenant no puede ver datos de otro, ni siquiera por bug del developer. Tres capas: RLS, JPA filter, ThreadLocal context. |
| **Idempotencia** | Propiedad de una operación: ejecutarla N veces tiene el mismo efecto que ejecutarla 1 vez. Esencial en sync offline. |
| **UUID v7** | Universally Unique Identifier ordenado por tiempo. Mejor que v4 para inserciones en B-tree de PostgreSQL. |
| **Idempotency-Key** | Header HTTP que el cliente envía para que el server reconozca reintentos. |
| **Strategy pattern** | Patrón de diseño que permite cambiar implementación sin modificar el llamador. Usado para extensibilidad de exporters, capturers, etc. |
| **JSONB** | Tipo de columna PostgreSQL que almacena JSON binario indexable. Usado para configuración versátil de política. |
| **Cursor-based pagination** | Paginación basada en cursor opaco (no offset). Resistente a inserts en medio del scroll. |
| **Problem+JSON (RFC 7807)** | Estándar de formato para errores HTTP. Usado en todas las APIs del proyecto. |
| **SnapStart** | Característica de AWS Lambda Java que reduce cold starts vía snapshots. **No usado en MVP** (vamos monolito), pero opción futura. |

### Acrónimos misceláneos

| Acrónimo | Significado |
|---|---|
| MVP | Minimum Viable Product |
| ADR | Architecture Decision Record |
| DoD | Definition of Done |
| RLS | Row-Level Security |
| TDE | Transparent Data Encryption (RDS) |
| KMS | Key Management Service (AWS) |
| ALB | Application Load Balancer (AWS) |
| ECS | Elastic Container Service (AWS) |
| RDS | Relational Database Service (AWS) |
| SaaS | Software as a Service |
| PII | Personally Identifiable Information |
| INAI | Instituto Nacional de Transparencia, Acceso a la Información y Protección de Datos Personales |
| LFPDPPP | Ley Federal de Protección de Datos Personales en Posesión de los Particulares |
| LFT | Ley Federal del Trabajo |
| NOM | Norma Oficial Mexicana |
| HE | Horas extra |
| RH | Recursos Humanos |
| OT | Overtime (horas extra) |
| SaaS | Software as a Service |
| SLA | Service Level Agreement |
| SDK | Software Development Kit |

## Decisiones operativas que dan forma al producto

Las siguientes decisiones, tomadas durante el diseño, son responsables del shape del MVP. Cualquier desviación requiere ADR.

1. **Captura biométrica difiere a v1.1.** MVP usa NFC + PIN en kiosko ZKTeco. La biometría se activa cuando el motor de captura, sync y cálculo ya esté validado en producción.
2. **Configuración por planta es central.** No es un nice-to-have: condiciona arquitectura, modelo de datos, y onboarding. Toda regla operativa debe poder cambiar por planta sin redeploy.
3. **2 perfiles default cubren el 80% de casos.** "Industrial estándar" y "Administrativo". Editor visual de política se difiere a v1.1.
4. **Eventos crudos son inmutables.** Las correcciones son nuevos registros que invalidan lógicamente el original. Esto da auditoría perfecta y reproducibilidad legal ([ADR-002](./adrs/ADR-002-eventos-inmutables-correcciones-append-only.md)).
5. **Cálculo de horas se separa de captura.** Captura es endpoint rápido, idempotente. Cálculo es job nocturno con snapshot de política aplicada.
6. **Multi-tenant desde día 1.** Aunque el primer cliente es uno solo, el código nace con tenant_id y RLS para evitar refactor masivo en cliente #2.

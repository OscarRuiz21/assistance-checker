# Riesgos y runbooks operativos

## Risk register top-10

Probabilidad y impacto en escala 1-5. Riesgo total = P × I.

### 🔴 Riesgo 1: Firmware K50 Pro sin soporte ADMS

| | |
|---|---|
| Probabilidad | 2 (media-baja con K50 Pro 2022+) |
| Impacto | 5 (catastrófico — no funciona el plan) |
| Total | 10 |
| Detección | Spike W3-W4 |
| Responsable | Tech Lead |
| Plan B | Cambiar modelo o activar bridge Windows ([ADR-007](./adrs/ADR-007-windows-bridge-plan-b.md)) |

### 🔴 Riesgo 2: Motor de cálculo de horas excede plazo W7-W8

| | |
|---|---|
| Probabilidad | 4 (alta — son 2 semanas para algo complejo) |
| Impacto | 4 (alto — bloquea export y go-live) |
| Total | 16 |
| Detección | Daily standup W7, sprint review W7 |
| Responsable | Tech Lead |
| Plan B | Cortar variantes complejas (Sunday premium "all_who_work", split de cross-midnight) |

### 🔴 Riesgo 4: Baja de un ingeniero (equipo de 3 sin redundancia)

| | |
|---|---|
| Probabilidad | 2 |
| Impacto | 5 (catastrófico) |
| Total | 10 |
| Detección | inevitable cuando ocurre |
| Responsable | Tech Lead / contratante |
| Mitigación preventiva | Pair-coding obligatorio en módulos críticos desde W1; documentación inline |
| Plan B | Contractor 2-3 semanas (mercado MX tiene Java seniors a 2 sem vista) |

### 🟡 Riesgo 5: RLS de Postgres rompe queries complejas

| | |
|---|---|
| Probabilidad | 3 |
| Impacto | 3 |
| Total | 9 |
| Detección | W2 al implementar |
| Plan B | RLS solo top-15 tablas, resto via Hibernate filter |

### 🟡 Riesgo 6: Conectividad de planta insuficiente

| | |
|---|---|
| Probabilidad | 3 |
| Impacto | 3 |
| Total | 9 |
| Detección | Visita técnica W0 |
| Plan B | Router 4G dedicado al kiosko |

### 🟡 Riesgo 7: CSV exportado no se importa correctamente en nómina del cliente

| | |
|---|---|
| Probabilidad | 3 |
| Impacto | 3 |
| Total | 9 |
| Detección | Demo W9 |
| Plan B | Personalizar columnas/formato del CSV. Soporta override en código del CsvExporter |

### 🟡 Riesgo 8: Multi-tenant data leak por bug del developer

| | |
|---|---|
| Probabilidad | 2 (con 3 capas de defensa) |
| Impacto | 5 (catastrófico — incidente legal) |
| Total | 10 |
| Detección | Test cross-tenant automatizado en CI |
| Mitigación | RLS + JPA filter + ThreadLocal + tests dedicados |

### 🟢 Riesgo 9: Tarifas AWS rebasan presupuesto

| | |
|---|---|
| Probabilidad | 2 |
| Impacto | 2 |
| Total | 4 |
| Detección | Budget alerts en cuenta AWS |
| Plan B | Optimización: t3.small en dev, single-AZ en staging |

### 🟢 Riesgo 10: Distribuidor ZKTeco no entrega a tiempo

| | |
|---|---|
| Probabilidad | 3 |
| Impacto | 3 |
| Total | 9 |
| Detección | Tracking del pedido |
| Plan B | Tener distribuidor secundario identificado; pedir demo unit prestado para no bloquearse |

## Plan de slip por hito

### Semáforo W6 (post Captura end-to-end)

```
🟢 VERDE
  Captura funciona end-to-end (NFC + PIN + offline + sync).
  Continuar plan original.

🟡 AMARILLO
  Captura funciona pero con bugs o latencia alta.
  Acciones:
    - Cortar review queue rica → lista plana en W10.
    - Cortar dashboard agregado → tabla simple.
    - Buffer de 1 sem extra de hardening en W11.

🔴 ROJO
  Captura no funciona aún.
  Acciones:
    - Re-evaluar plazo con cliente.
    - Negociar:
      • Quitar segundo tenant (1 piloto, no 2).
      • Quitar modo rescate (papel + captura supervisor manual).
      • Quitar dashboard.
      • Posponer cierre automático periodos (cierre manual).
```

### Semáforo W8 (post Cálculo de horas)

```
🟢 VERDE
  Cálculo correcto en 80% casos. Continuar.

🟡 AMARILLO
  Cálculo correcto en happy path; edge cases pendientes.
  Acciones:
    - Documentar limitaciones conocidas con cliente.
    - Edge cases pasan a v1.1.

🔴 ROJO
  Cálculo no funcional.
  Acciones:
    - MVP cambia: cálculo manual con eventos exportables a CSV.
    - Cliente hace cálculo en su nómina o Excel.
    - v1.1 entrega motor automático.
    - Esto es fail-safe — el producto sigue siendo útil
      (registro digital de eventos auditados).
```

## Runbook 1: Tenant onboarding manual (MVP)

```
PRE-ONBOARDING
  ☐ Cliente designó champion con autoridad.
  ☐ Lista inicial de empleados (CSV con columnas mínimas).
  ☐ Lista de plantas y turnos.
  ☐ Aviso de privacidad LFPDPPP redactado y aprobado por legal.
  ☐ Designación de oficial protección de datos del tenant.

EJECUCIÓN (todo desde admin SQL connect a prod RDS, sesión auditada)

1. Crear tenant
   INSERT INTO tenant (id, name, rfc, contact_email, timezone, status)
   VALUES (gen_random_uuid_v7(), 'Empresa SA', 'EMP010101A1B', 
           'admin@empresa.com', 'America/Mexico_City', 'active');

2. Crear primer admin user en Cognito (manualmente desde AWS console)
   Email: admin@empresa.com
   Temp password: <generado>
   Forzar cambio de password al primer login.

3. Crear app_user correspondiente
   INSERT INTO app_user (id, tenant_id, cognito_sub, email, full_name, role, status)
   VALUES (gen_random_uuid_v7(), <tenant_id>, <cognito_sub>, 
           'admin@empresa.com', 'Admin Inicial', 'tenant_admin', 'active');

4. Crear plantas
   INSERT INTO plant (id, tenant_id, name, code, address, policy_profile_id, ...)
   VALUES ...;

5. Asignar perfil de política a cada planta
   INSERT INTO plant_policy_history (id, tenant_id, plant_id, profile_id,
                                      override, effective_from)
   VALUES ...;

6. Crear turnos
   INSERT INTO shift (id, tenant_id, plant_id, name, type, start_time, end_time, ...)
   VALUES ...;

7. Importar empleados (script bulk)
   Usar script `scripts/import_employees.py <tenant_id> <csv_file>`.
   Valida CURP, RFC, NSS antes de insertar.

8. Generar PINs iniciales (si aplica)
   Script `scripts/generate_pins.py <tenant_id>` genera PIN aleatorio
   y entrega lista CSV para el cliente distribuir.

9. Email al admin del tenant
   Subject: Bienvenido a Asistencia Checker
   Body:
     - URL de admin web
     - Email + temp password (envío seguro, no en email plano)
     - Próximos pasos: cambiar password, configurar MFA (v1.1),
       distribuir PINs/tarjetas a empleados.

POST-ONBOARDING
  ☐ Documentar tenant_id en CRM/registro interno.
  ☐ Activar monitoreo específico del tenant.
  ☐ Notificación de "tenant nuevo" a equipo soporte.
```

## Runbook 2: Pairing de kiosko en planta

### Web Kiosko Mode (tablet/PC en planta)

```
1. Tech del cliente / supervisor abre admin web.
2. Va a Kioskos → "Nuevo Kiosko" → tipo "Web Kiosko".
3. Llena: nombre, planta, métodos habilitados.
   Backend inserta fila en `kiosko` (type='web_kiosko') + fila vacía en `kiosko_web`.
4. Click "Generar código de pairing".
   Backend genera pairing_code, lo asocia al kiosko_id, set `kiosko_web.pairing_expires_at`.
5. Sistema muestra código (ej: "ABCD-1234-EFGH") + QR.
6. En el dispositivo destinado a kiosko:
   - Abrir browser en URL https://app.tudominio.com/kiosko-mode
   - Escanear QR o ingresar código manualmente.
7. App pide credenciales de device:
   POST /api/v1/auth/kiosko/pair { pairing_code, user_agent, app_version }
   Backend persiste user_agent y bcrypt(device_token) en kiosko_web.
8. Backend retorna device_token, persiste en localStorage encriptado.
9. App entra en modo full-screen, listo para captura.
10. Test: marcar checada de prueba con un empleado.
11. Verificar en admin web que aparece el evento.
```

### ZKTeco K50 Pro

```
Pre-instalación:
  - Hardware en mano, energía + red (cable o Wi-Fi disponible).
  - SN del aparato anotado.

1. Encender K50 Pro.
2. Configuración inicial:
   Menu → System → Date/Time (zona horaria del tenant)
   Menu → System → Language: Español
   Menu → Comm → Network: configurar IP/Wi-Fi
   Menu → Comm → Cloud Server Setting:
     Server Address: api.tudominio.com
     Server Port: 443
     Enable Domain Name: ON
     HTTPS: ON
3. En admin web: Kioskos → Nuevo Kiosko (type: zkteco_k50)
   Llenar: nombre, planta, device_serial (SN).
   Backend inserta fila en `kiosko` (type='zkteco_k50') + fila en `kiosko_zkteco`
   con device_serial, en la misma transacción.
   Marcar status: active.
4. K50 Pro debe empezar a hacer heartbeat al backend automáticamente.
   Verificar en logs: grep "iclock/getrequest" → SN aparece.
5. Sync inicial:
   admin web → Kioskos → este kiosko → "Forzar sync"
   Encola comandos add_user para todos los empleados de la planta.
6. K50 Pro consume comandos en próximos ~60s.
7. Test: poner huella de un empleado enrolado, verificar evento llega.
```

## Runbook 3: Cierre de pay period

```
PRE-CIERRE (T-3 días antes del fin de periodo)
  ☐ Anuncio a supervisores: "Periodo X cierra el viernes".
  ☐ Resolver review queue pendiente del periodo.
  ☐ Confirmar correcciones manuales necesarias.

DÍA DEL CIERRE

1. RH entra a admin web → Pay Periods.
2. Selecciona periodo abierto: "Semanal: 2026-04-20 a 2026-04-26".
3. Sistema muestra preview:
   - Total empleados: 487
   - Días incompletos: 3 (warning amarillo)
   - Review queue pendiente: 0 (verde)
   - Empleados sin checadas: 0 (verde)
4. RH revisa los 3 días incompletos:
   - 1 caso real: empleado se enfermó, justificar como ausencia.
   - 2 casos: faltó check-out, registrar manualmente con motivo.
5. Refresh preview: 0 incompletos, todo verde.
6. Click "Cerrar periodo" → confirmación modal.
7. Backend:
   - Genera pay_period_summary por empleado.
   - Marca pay_period.status = 'closed'.
   - Trigger audit_log.
   - Pre-firma URL de S3 con CSV listo.
8. RH descarga CSV.
9. RH importa CSV en sistema de nómina externo.
10. Verifica importación, paga nómina.

POST-CIERRE (T+3 días)
  - pay_period.status pasa de 'closed' a 'locked' automáticamente
    (cron job nocturno que aplica policy.pay_period.lock_after_close_days).
  - Después de lock, no se puede reabrir sin proceso especial.

EXCEPCIÓN: REAPERTURA (solo tenant_admin/RH)
  Si tras lock se descubre error:
  POST /api/v1/pay-periods/{id}/reopen
  Body: { "reason": "Corrección detectada por auditor" }
  
  Genera audit_log entry con autor + razón.
  Periodo vuelve a 'closed'.
  RH hace correcciones, vuelve a cerrar (genera nueva versión del summary).
```

## Runbook 4: Disaster recovery

### Escenario A: Falla de RDS (instancia primaria caída)

```
1. AWS RDS Multi-AZ ejecuta failover automático (~60s).
2. CloudWatch alarma dispara, equipo recibe notificación.
3. ECS task se reconnecta automáticamente al nuevo primary.
4. Verificar:
   - admin web responde
   - kioskos siguen sincronizando
   - logs no muestran errores de conexión persistentes
5. Post-mortem: revisar causa root del failover.
```

### Escenario B: ECS task crashes

```
1. ALB health check falla → marca task unhealthy.
2. ECS service replaces task automáticamente (~60s).
3. Verificar:
   - GET /api/v1/health responde 200
   - tasks running count = desired count
4. Si crashes repetidos:
   - Revisar logs del task (CloudWatch).
   - Revisar memory/CPU metrics.
   - Si es bug, rollback a última versión estable.
```

### Escenario C: Pérdida de datos parcial (último cambio se perdió)

```
1. Identificar ventana exacta del problema (logs de aplicación + CloudTrail).
2. Restore from PITR (Point-in-Time Recovery):
   - AWS RDS → instancia → Restore to point in time.
   - Crear DB nueva con timestamp anterior al problema.
3. Comparar datos para identificar exactamente qué se perdió.
4. Aplicar correcciones manuales en producción O cambiar a DB restaurada
   (trade-off: tiempo perdido vs datos perdidos).
5. Post-mortem público al cliente afectado.
```

### Escenario D: Tenant data leak detectado

```
1. AISLAR: cambiar status de tenant afectado a 'suspended' inmediatamente.
2. NOTIFICAR al cliente (LFPDPPP obliga aviso ASAP).
3. INVESTIGAR:
   - audit_log del periodo sospechoso.
   - CloudTrail.
   - Logs de aplicación.
4. CONTENER: revocar tokens activos, forzar re-login.
5. EVALUAR alcance: ¿qué datos? ¿de qué tenants? ¿qué cantidad?
6. NOTIFICAR a INAI si aplica (LFPDPPP Art. 20: aviso a titulares + autoridad).
7. POST-MORTEM detallado, RCA, plan de remediación.
8. PARCHE técnico (test que reproduzca el bug + fix).
```

### Escenario E: Caída completa de región AWS

```
MVP no tiene multi-region. Recovery toma horas.

1. Notificación al cliente: "AWS está caído, captura local sigue funcionando".
2. Kioskos siguen capturando offline (su buffer absorbe).
3. Esperar a que AWS resuelva (típicamente 1-4h).
4. Cuando vuelva: kioskos sincronizan automáticamente, no se pierde nada.
5. POST-MORTEM: evaluar si justifica multi-region en v2.
```

## Procedimientos de soporte

### Triage de tickets

```
P0 (page on-call inmediato):
  - Backend caído (5xx > 50%)
  - Captura no funciona en planta piloto
  - Data leak sospechoso
  
P1 (resolver en 4h):
  - Latencia degradada (p99 > 5s)
  - Cierre de periodo bloqueado
  - Kiosko piloto offline > 2h
  
P2 (resolver en 24h):
  - Bugs UX
  - Reportes con datos incorrectos no urgentes
  - Performance issue puntual
  
P3 (resolver en sprint):
  - Mejoras solicitadas
  - Cosmetic fixes
```

### Contactos del cliente

```
Champion del tenant: <persona>
Email: <email>
Teléfono: <teléfono>
Slack/WhatsApp: <link>
Horario de atención: <horario>

Backup del champion: <persona alterna>
```

## Métricas operativas a monitorear

```
DAILY (revisión 9 am):
  ├─ Eventos recibidos en últimas 24h (counter)
  ├─ Error rate global
  ├─ Kioskos online (count)
  ├─ Review queue pendiente
  └─ Issues abiertos por cliente

WEEKLY (revisión lunes):
  ├─ p99 latency POST /events
  ├─ DB connections sustained
  ├─ Storage usage RDS
  ├─ AWS bill semana actual
  └─ Tendencia de bug rate

MONTHLY (revisión mes):
  ├─ Tenants activos
  ├─ Eventos totales del mes
  ├─ Empleados activos
  ├─ Periodos cerrados
  ├─ ARCO requests procesados
```

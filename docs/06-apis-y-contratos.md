# APIs y contratos

## Principios de diseño

### 1. Versionado en URL: `/api/v1/...`

- v1 vive ≥ 18 meses tras lanzamiento de v2.
- Endpoints deprecados llevan headers `Deprecation: true` y `Sunset: <date>`.
- Cambios aditivos (campos opcionales) NO son breaking. Eliminaciones o renames sí.

### 2. Tenant resuelto desde la identidad

- URL: `/api/v1/employees`, **no** `/api/v1/tenants/{tenantId}/employees`.
- Token contiene `tenant_id` en claims.
- Backend hace `SET app.tenant_id = ...` desde JWT → RLS aplica.
- Si cliente manda `tenant_id` en body o URL, **se ignora**.

### 3. Idempotencia

- **Eventos**: `id` UUID v7 en body es la clave. UPSERT idempotente.
- **Mutaciones**: header `Idempotency-Key: <uuid>` obligatorio en POST/PUT/PATCH no-GET.
- Tabla `idempotency_record` cachea respuesta 24h.

### 4. Errores: RFC 7807 Problem+JSON

```json
{
  "type": "https://api.attendance.mx/errors/employee-blocked",
  "title": "Employee account is blocked",
  "status": 422,
  "detail": "Employee EMP-00123 is in 'terminated' status",
  "instance": "/api/v1/events",
  "trace_id": "01HX2K9...",
  "errors": [
    { "field": "employee_identifier.value", "code": "TERMINATED" }
  ]
}
```

### 5. Paginación cursor-based universal

```
GET /api/v1/employees?limit=100&cursor=eyJ0aW1lIjoi...
→ { "items": [...], "next_cursor": "eyJ..." | null }
```

Cursor opaco firmado HMAC con `(last_id, last_created_at)`. Sin offset jamás.

### 6. Headers transversales

| Header | Uso |
|---|---|
| `Authorization: Bearer <jwt>` | Auth |
| `X-Trace-Id` | Propaga trace end-to-end |
| `X-Request-Id` | ID de request, server lo genera si no viene |
| `Idempotency-Key` | Mutaciones repetibles |
| `If-Match: <etag>` | Optimistic locking en updates |
| `Accept-Language` | `es-MX` default |
| `User-Agent` | Cliente identifica app + versión |

## Autenticación

### Usuarios (admin/supervisor/RH)

```http
POST /api/v1/auth/login
Content-Type: application/json
{
  "email": "supervisor@empresa.com",
  "password": "..."
}

→ 200 OK
{
  "access_token": "eyJhbGc...",
  "refresh_token": "eyJhbGc...",
  "expires_in": 3600,
  "token_type": "Bearer",
  "user": {
    "id": "...",
    "email": "...",
    "role": "supervisor",
    "tenant_id": "...",
    "plant_ids": ["...", "..."]
  }
}
```

JWT claims:
```json
{
  "sub": "user_uuid",
  "tenant_id": "...",
  "role": "supervisor",
  "plant_ids": ["plant1", "plant2"],
  "iat": 1714238400,
  "exp": 1714242000
}
```

Endpoints adicionales:
- `POST /api/v1/auth/refresh` — refresh token flow
- `POST /api/v1/auth/logout` — invalida refresh token
- `POST /api/v1/auth/change-password`
- `POST /api/v1/auth/reset-password` — flujo Cognito

### Web kiosko (pairing)

```http
# Admin genera código de pairing:
POST /api/v1/kioskos/{kiosko_id}/pair-code
Authorization: Bearer <admin_jwt>

→ 201 Created
{
  "pairing_code": "ABCD-1234-EFGH",
  "expires_at": "...",
  "valid_for_minutes": 60
}

# Web kiosko intercambia código por device_token:
POST /api/v1/auth/kiosko/pair
{
  "pairing_code": "ABCD-1234-EFGH",
  "user_agent": "Chrome/...",
  "app_version": "1.0.0"
}
# Notas:
# - El web kiosko NO tiene device_serial (es un concepto del K50). Al canjear el
#   pairing_code, el backend persiste user_agent en kiosko_web.user_agent y
#   guarda el bcrypt del device_token en kiosko_web.pairing_token_hash.
# - El device_token es el JWT firmado RSA que el cliente usa de aquí en adelante.

→ 200 OK
{
  "device_token": "eyJ...",
  "kiosko_id": "...",
  "tenant_id": "...",
  "plant_id": "...",
  "server_time": "...",
  "initial_sync_url": "/api/v1/kiosko/sync"
}
```

Device JWT firmado RSA (no Cognito), claims: `kiosko_id`, `tenant_id`, `plant_id`, `iat`, `kid`. **Sin `exp`** (kiosko opera indefinido). Revocación server-side via `kiosko.status = 'retired'`.

### Hardware ZKTeco (ADMS)

Sin auth Bearer. Validación por **Serial Number** del aparato registrado en `kiosko_zkteco.device_serial` (subtabla específica del tipo K50, ver [03-modelo-datos.md §4.2](./03-modelo-datos.md)). Cualquier petición a `/iclock/*` con SN no registrado retorna `404`.

```http
POST /iclock/cdata?SN=K50PRO123ABC&table=ATTLOG&Stamp=9999
```

Detalle del protocolo: ver [09-hardware-zkteco.md](./09-hardware-zkteco.md).

## Capa A: APIs del Kiosko (web kiosko + futuro Android)

### POST /api/v1/events — Captura individual

```http
POST /api/v1/events
Authorization: Bearer <device_token>
{
  "id": "0194d3b2-7c8e-7f2a-9123-abcdef012345",
  "type": "check_in",
  "capture_method": "nfc",
  "device_time": "2026-04-27T14:32:01.234Z",
  "device_offset_ms": 2222,
  "employee_identifier": {
    "type": "nfc_uid",
    "value": "04:A3:B2:C1:D5:E8:90"
  },
  "metadata": {
    "device_battery": 87,
    "app_version": "1.0.0"
  }
}
```

`employee_identifier.type`: `nfc_uid`, `employee_number`, `fingerprint` (v1.1), `face` (v1.1).

Respuestas:

```http
# Nuevo
HTTP/1.1 201 Created
Location: /api/v1/events/0194d3b2-...
{
  "id": "...",
  "received_at": "...",
  "event_time_corrected": "...",
  "is_duplicate": false,
  "warnings": []
}

# Idempotente (kiosko reintentó)
HTTP/1.1 200 OK
{
  "id": "...",
  "received_at": "<original timestamp>",
  "is_duplicate": true
}
```

Errores:
- `422 EMPLOYEE_NOT_FOUND` — empleado no en tenant.
- `422 EMPLOYEE_TERMINATED` — empleado dado de baja.
- `422 NFC_BLOCKED` — tarjeta bloqueada.
- `422 KIOSKO_PLANT_MISMATCH` — empleado sin permiso multi-planta.
- `410 KIOSKO_REVOKED` — kiosko `status='retired'`.

### POST /api/v1/events/batch — Batch para sync de buffer

```http
POST /api/v1/events/batch
{
  "events": [
    { "id": "...", ... },
    ...   // hasta 200 eventos
  ]
}

→ 207 Multi-Status
{
  "results": [
    { "id": "...", "status": 201 },
    { "id": "...", "status": 200, "is_duplicate": true },
    { "id": "...", "status": 422, "error": { "code": "EMPLOYEE_NOT_FOUND" } }
  ],
  "summary": { "total": 50, "created": 47, "duplicate": 2, "failed": 1 }
}
```

Cada evento es independiente; batch nunca falla entero por uno malo.

### GET /api/v1/kiosko/sync — Sync inverso

```http
GET /api/v1/kiosko/sync?since=2026-04-27T10:00:00Z
Authorization: Bearer <device_token>

→ 200 OK
{
  "server_time": "2026-04-27T15:00:00.123Z",
  "data_version": 142,
  "since_supplied": "2026-04-27T10:00:00.000Z",
  
  "employees": {
    "added": [
      {
        "id": "...",
        "employee_number": "EMP-00456",
        "full_name": "...",
        "default_shift_id": "...",
        "pin_hash": "$2a$10$..."
      }
    ],
    "updated": [...],
    "removed": ["...", "..."]
  },
  
  "nfc_cards": {
    "added": [{ "uid": "...", "employee_id": "..." }],
    "blocked": ["uid1", "uid2"],
    "removed": []
  },
  
  "shifts": { "added": [...], "updated": [...], "removed": [] },
  
  "policy": {
    "version": 17,
    "changed": false,
    "config": null
  },
  
  "calendar": {
    "added": [
      { "date": "2026-05-01", "name": "Día del Trabajo", "type": "lft_official" }
    ],
    "removed": []
  },
  
  "kiosko_config": {
    "enabled_methods": ["nfc", "pin"],
    "anti_double_tap_seconds": 30,
    "heartbeat_interval_seconds": 60,
    "sync_interval_seconds": 300
  },
  
  "next_sync_recommended_in_seconds": 300
}
```

Si delta enorme, respuesta paginada con `partial: true` y `next_cursor`.

### POST /api/v1/kiosko/heartbeat

```http
POST /api/v1/kiosko/heartbeat
Authorization: Bearer <device_token>
{
  "device_time": "2026-04-27T14:32:01.234Z",
  "events_pending_local": 3,
  "battery_pct": 87,
  "app_version": "1.0.0"
}

→ 200 OK
{
  "server_time": "2026-04-27T14:32:03.456Z",
  "offset_ms": 2222,
  "config_version": 17,
  "should_resync": false,
  "alerts": []
}
```

## Capa B: APIs Admin (CRUD)

Todas requieren JWT user con role apropiado.

### Empleados

```
GET    /api/v1/employees                       (filter ?plant_id=&status=&search=)
GET    /api/v1/employees/{id}
POST   /api/v1/employees                       (Idempotency-Key)
PATCH  /api/v1/employees/{id}                  (If-Match: <etag>)
DELETE /api/v1/employees/{id}                  (soft delete → terminated)
POST   /api/v1/employees/{id}/restore
POST   /api/v1/employees/{id}/pin              (set/change PIN)
POST   /api/v1/employees/{id}/transfer         (cambio de planta)
GET    /api/v1/employees/{id}/audit-log
POST   /api/v1/employees/import-csv            (bulk import async, retorna job_id)
```

### Plantas

```
GET    /api/v1/plants
GET    /api/v1/plants/{id}
POST   /api/v1/plants
PATCH  /api/v1/plants/{id}
DELETE /api/v1/plants/{id}

GET    /api/v1/plants/{id}/policy              (efectiva = profile + override)
PUT    /api/v1/plants/{id}/policy              (set profile + override)
GET    /api/v1/plants/{id}/policy/history
GET    /api/v1/plants/{id}/dashboard           (métricas tiempo real)
```

### Kioskos

```
GET    /api/v1/kioskos
GET    /api/v1/kioskos/{id}
POST   /api/v1/kioskos
PATCH  /api/v1/kioskos/{id}
DELETE /api/v1/kioskos/{id}                    (status → retired)
POST   /api/v1/kioskos/{id}/pair-code          (genera código de pairing web)
POST   /api/v1/kioskos/{id}/revoke
GET    /api/v1/kioskos/{id}/health             (último heartbeat, alertas)
GET    /api/v1/kioskos/{id}/heartbeats
```

### NFC Cards

```
GET    /api/v1/nfc-cards
POST   /api/v1/nfc-cards                       (registra UID, asigna a empleado)
PATCH  /api/v1/nfc-cards/{id}                  (block/unblock)
DELETE /api/v1/nfc-cards/{id}
```

### Turnos y calendario

```
GET    /api/v1/shifts                          (?plant_id=)
POST   /api/v1/shifts
PATCH  /api/v1/shifts/{id}
DELETE /api/v1/shifts/{id}

GET    /api/v1/holidays?year=2026&plant_id=
POST   /api/v1/holidays                        (custom para v1.1+)
DELETE /api/v1/holidays/{id}
```

### Políticas

```
GET    /api/v1/policy-profiles                 (lista MVP: industrial_standard + admin_office)
GET    /api/v1/policy-profiles/{id}
POST   /api/v1/policy-profiles                 (solo super_admin)
PATCH  /api/v1/policy-profiles/{id}
```

### Eventos (consulta admin)

```
GET    /api/v1/events                          (filtros amplios, ?employee_id=&plant_id=&from=&to=)
GET    /api/v1/events/{id}
GET    /api/v1/events/{id}/audit-trail
```

### Correcciones

```
POST   /api/v1/corrections                     (Idempotency-Key)
GET    /api/v1/corrections
GET    /api/v1/corrections/{id}
```

Body de correction:
```json
{
  "action": "create",
  "employee_id": "...",
  "plant_id": "...",
  "event_type": "check_in",
  "event_time": "2026-04-27T06:05:00-06:00",
  "reason": "Empleado olvidó checar; confirmado por video"
}
```

`action`: `create`, `invalidate`, `adjust_time`, `adjust_type`.

### Review Queue

```
GET    /api/v1/review-queue?status=pending&plant_id=
GET    /api/v1/review-queue/{id}
POST   /api/v1/review-queue/{id}/resolve
POST   /api/v1/review-queue/{id}/dismiss
```

### Asistencia

```
GET    /api/v1/attendance/days                 (?employee_id=&plant_id=&from=&to=)
GET    /api/v1/attendance/days/{id}
POST   /api/v1/attendance/recalculate          (async, retorna job_id)
```

### Pay periods

```
GET    /api/v1/pay-periods?status=
GET    /api/v1/pay-periods/{id}
POST   /api/v1/pay-periods/{id}/close          (validaciones; force=true salta)
POST   /api/v1/pay-periods/{id}/lock
POST   /api/v1/pay-periods/{id}/reopen         (solo tenant_admin/rh)
GET    /api/v1/pay-periods/{id}/summary
GET    /api/v1/pay-periods/{id}/export?format=csv&plant_id=
```

### Audit log

```
GET    /api/v1/audit-log                       (?actor_user_id=&entity_type=&entity_id=&action=)
```

### Modo rescate

```
POST   /api/v1/rescue/events                   (Idempotency-Key)
```

Body:
```json
{
  "events": [
    {
      "id": "...",
      "employee_id": "...",
      "type": "check_in",
      "event_time": "2026-04-27T06:05:00-06:00",
      "plant_id": "...",
      "reason": "Kiosko principal sin energía"
    }
  ]
}
```

Cada evento se persiste con `capture_method='manual'` y `recorded_by_user_id`.

### Jobs async

```
GET    /api/v1/jobs/{id}                       (estado de job: pending/running/completed/failed)
```

### Health

```
GET    /api/v1/health
GET    /api/v1/health/ready
GET    /api/v1/health/live
```

## Capa C: APIs Supervisor (subset con restricciones)

Mismo conjunto que admin pero con estas reglas:

1. **Filtrado automático por `plant_ids`** del JWT en todos los GET.
2. **Endpoints prohibidos**:
   - `POST /pay-periods/.../reopen`
   - `DELETE` sobre cualquier entidad
   - `POST /policy-profiles`
   - `POST /employees`, `PATCH /employees`
3. **Permitidos con filtro de planta**:
   - GET sobre eventos / asistencia / review queue (solo de sus plantas).
   - POST corrections.
   - POST review-queue/resolve.
   - POST rescue/events.
   - GET dashboard de su(s) planta(s).

## Push Adapter ZKTeco (Capa D)

Endpoints recibidos del K50 Pro vía protocolo ADMS:

```
GET    /iclock/cdata?SN=...&options=all              (device pide config)
POST   /iclock/cdata?SN=...&table=ATTLOG&Stamp=...   (device envía eventos)
POST   /iclock/cdata?SN=...&table=OPERLOG&Stamp=...  (device envía operaciones)
GET    /iclock/getrequest?SN=...&INFO=...            (device pide comandos pendientes)
POST   /iclock/devicecmd?SN=...                      (device confirma ejecución de comando)
POST   /iclock/edata?SN=...&type=fp                  (device sube template biométrica)
```

**Sin Bearer auth**. Validación por SN registrado en `kiosko_zkteco.device_serial` (resuelto vía JOIN con `kiosko` para verificar `status='active'`). Si SN no existe o el kiosko no está activo → 404.

Detalle: ver [09-hardware-zkteco.md](./09-hardware-zkteco.md) y diseño dedicado del Push Adapter (pendiente).

## Rate limiting

| Endpoint group | Límite | Bucket |
|---|---|---|
| `POST /events` | 120/min | per kiosko |
| `POST /events/batch` | 20/min, max 200 events | per kiosko |
| `GET /kiosko/sync` | 12/min | per kiosko |
| `POST /kiosko/heartbeat` | 90/min | per kiosko |
| `POST /auth/login` | 5/min | per IP, lockout 15m tras 10 fallos |
| `POST /auth/refresh` | 60/min | per user |
| `GET /events` admin query | 60/min | per user |
| `POST /attendance/recalculate` | 5/min | per tenant |
| Resto admin CRUD | 600/min | per user |
| `/iclock/*` ADMS | sin límite (controlado por kiosko) | — |

Headers de respuesta:
```
X-RateLimit-Limit: 120
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1714242060
Retry-After: 30   (solo en 429)
```

## Códigos HTTP

| Código | Cuándo |
|---|---|
| 200 | OK / idempotente duplicado |
| 201 | Recurso creado |
| 202 | Aceptado para procesamiento async |
| 204 | Eliminación exitosa, sin body |
| 207 | Multi-Status (batch) |
| 304 | Not Modified (sync con ETag) |
| 400 | Schema/parsing error |
| 401 | Sin token / inválido / expirado |
| 403 | Token válido, rol no autorizado |
| 404 | No existe (en este tenant) |
| 409 | ETag mismatch / idempotency conflict |
| 410 | Gone (kiosko revocado, empleado terminado) |
| 422 | Validación de negocio |
| 429 | Rate limit |
| 500 | Server error genérico |
| 502 | Upstream error |
| 503 | Mantenimiento |

## Códigos machine-readable de error

```yaml
EMPLOYEE_NOT_FOUND:        { http: 404, retriable: false }
EMPLOYEE_TERMINATED:       { http: 422, retriable: false }
EMPLOYEE_ON_LEAVE:         { http: 422, retriable: false }
NFC_BLOCKED:               { http: 422, retriable: false }
NFC_NOT_ASSIGNED:          { http: 422, retriable: false }
KIOSKO_PLANT_MISMATCH:     { http: 422, retriable: false }
KIOSKO_REVOKED:            { http: 410, retriable: false }
KIOSKO_PAIRING_EXPIRED:    { http: 422, retriable: false }
POLICY_VERSION_OUTDATED:   { http: 409, retriable: true, action: "resync" }
PAY_PERIOD_LOCKED:         { http: 422, retriable: false }
PAY_PERIOD_INCOMPLETE:     { http: 422, retriable: false }
ETAG_MISMATCH:             { http: 409, retriable: true, action: "refetch" }
RATE_LIMITED:              { http: 429, retriable: true }
INVALID_PAIRING_CODE:      { http: 422, retriable: false }
DUPLICATE_NFC_UID:         { http: 422, retriable: false }
INVALID_PIN:               { http: 422, retriable: false }
PIN_TOO_WEAK:              { http: 422, retriable: false }
```

## OpenAPI 3 + SDK gen

- **SpringDoc** anota controllers/DTOs.
- Spec en `/v3/api-docs.json`, UI en `/swagger-ui.html` (ambientes no-prod).
- En CI: `openapi-generator` produce SDK TypeScript (admin web) y Kotlin (Android futuro).
- Spec versionada en repo separado `api-contracts/` para que mobile/web no dependan del backend repo.

## Observabilidad por endpoint

Cada request emite a CloudWatch:
- `latency_ms` histogram.
- `status_code` counter por valor.
- Dimensiones: `tenant_id`, `endpoint`, `method`.
- `kiosko_id` cuando aplica.

X-Ray traces en 100% de `POST /events`, 10% en otros.

Alarmas:
- p99 > 1000ms en `POST /events` → page on-call.
- 5xx > 1% en cualquier endpoint → ticket.
- 401/403 > 10% en endpoint → posible attack o bug cliente.

## Webhooks (placeholder v2)

Estructura preparada en código (Strategy `WebhookPublisher`, `NoOpPublisher` en MVP). Endpoints futuros:

```
POST   /api/v1/webhooks
GET    /api/v1/webhooks
DELETE /api/v1/webhooks/{id}
POST   /api/v1/webhooks/{id}/test
```

Eventos publicables (v2): `event.created`, `attendance_day.calculated`, `pay_period.closed`, `correction.applied`.

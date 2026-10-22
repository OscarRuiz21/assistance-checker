# Flujos operativos y edge cases

Este documento captura las **decisiones de política** tomadas durante el diseño para 16 edge cases operativos. La mayoría se resuelven con configuración por planta, lo que hace al motor de cálculo agnóstico al edge case específico.

## Flujo principal: captura de evento

```
1. Obrero llega → identifica (NFC/PIN/futura biometría)
2. Kiosko valida localmente:
   • Empleado existe y está activo
   • Tarjeta no bloqueada
   • Anti-double-tap (30s ventana)
3. Kiosko persiste localmente con UUID v7
4. Kiosko muestra confirmación visual (< 3s)
5. Push asíncrono al backend
6. Backend UPSERT idempotente en event_raw
7. Sin cálculo en captura: solo persistencia
```

## Máquina de estados del día laboral

```
[SIN_CHECAR] ──check_in──> [EN_LABOR]
                              │
                              ├──lunch_out──> [EN_COMIDA]
                              │                  │
                              │              lunch_in
                              │                  │
                              │                  ▼
                              ├──check_out──> [JORNADA_TERMINADA]
                              │
                              └─(timeout fin de turno)──> [INCOMPLETO]
```

Las transiciones se infieren del último evento del empleado en el día contable. Eventos fuera de orden generan `review_queue.reason = 'multi_kiosko_conflict'`.

## Edge cases con políticas decididas

### 1. Falta entrada (obrero olvidó checar entrada pero sí checó salida)

**Política**: `marca día como incompleto + revisión supervisor`.

- `attendance_day.is_complete = false`
- Aparece en `review_queue` con `reason = 'missing_check_in'`.
- Supervisor decide: agregar entrada manual con motivo, o marcar falta.
- No se asume horario teórico.

### 2. Falta salida (obrero olvidó checar al salir)

**Política**: `cierre automático al fin del turno + revisión supervisor`.

- Al alcanzar la hora teórica de fin del turno + 1h de margen, sistema marca cierre automático.
- `attendance_day.is_complete = false`.
- `review_queue` con `reason = 'missing_check_out'`.
- Supervisor confirma o ajusta.

### 3. Doble checada en pocos segundos (anti double-tap)

**Política**: `ignora segunda dentro de 30s`.

- Validación local en kiosko (sin esperar backend).
- Configurable: `policy.tolerance.anti_double_tap_seconds`.
- En web kiosko mode también aplica.

### 4. Empleado checa en planta donde NO está asignado

**Política**: `permite con justificación + notifica supervisor`.

- Si `employee.multi_plant_allowed = true` o existe registro en `employee_plant` válido para hoy → acepta sin fricción.
- Si no → modal pide motivo, registra evento con `raw_payload.cross_plant = true`, notifica a ambos supervisores.
- Sin bloqueo: la flexibilidad operativa gana sobre la rigidez.

### 5. Biometría falla (cuando esté en v1.1)

**Política**: cadena de fallback `configurable por planta`. Default sugerido: `huella → PIN → gerente`.

- 3 intentos fallidos de huella → ofrece PIN.
- 3 intentos fallidos de PIN → escala a gerente (notificación).
- Re-enrolamiento en kiosko: solo con autorización supervisor.

### 6. Checada fuera de ventana de turno

**Política**: `registra normal, supervisor revisa al cierre`.

- Si turno es 6:00-14:00 y checa entrada a las 4:00 o salida a las 18:00 → registra.
- Marca `review_queue.reason = 'out_of_window'` (informativo).
- Cálculo de horas extras determina si esto es OT autorizado o no según política.

### 7. Tiempo de comida

**Política**: `configurable por planta`. 3 modos disponibles:

- `auto_deduct` — descuenta N minutos automáticamente, sin checada de comida.
- `manual_punch` — obrero checa salida y entrada de comida (4 eventos por día).
- `none` — comida fuera de jornada pagada.

Default sugerido: `auto_deduct: 30 min`.

### 8. Kiosko caído (hardware murió, sin energía, etc.)

**Política**: `app móvil obreros O supervisor registra todos, configurable por planta`.

- Modo "Rescate" del supervisor: registra checadas manualmente desde web responsive.
- Cada evento de rescate tiene `capture_method = 'manual'` y `recorded_by_user_id`.
- Audit log automático para cada uno.
- v2: app obrero permite checar desde celular con geofence.

### 9. Correcciones manuales retroactivas

**Política**: `cualquier supervisor con motivo + log de auditoría`.

- Sin doble aprobación en MVP.
- Motivo obligatorio (campo `reason` no nulo, mínimo 10 caracteres).
- Trigger automático de recálculo del día afectado.
- Periodo cerrado: bloquea correcciones (excepto reapertura por tenant_admin).

### 10. Turno nocturno cruza medianoche

**Política**: `configurable por planta/turno`. Default: `entry_day`.

- `entry_day` — todo el turno cuenta al día de la entrada (más común en MX).
- `exit_day` — al día de la salida.
- `split` — divide horas entre ambos días (más preciso, más complejo).

`shift.crosses_midnight = true` activa la lógica especial en cálculo.

### 11. Ausencias justificadas (vacaciones, incapacidad)

**Política**: `fuera de alcance MVP`. Se manejará en v1.1 o vía import desde sistema externo de RH.

- En MVP: si el empleado no checa, el día queda como `is_absent = true` y supervisor lo justifica manualmente.
- v1.1: módulo de permisos integrado.

### 12. Foto del kiosko (anti-fraude)

**Política**: `configurable por planta` pero **fuera de MVP** (v2).

- Estructura de datos preparada (`policy.photo_policy`).
- `EventEnricher` interface lista (NoOp en MVP, PhotoEnricher en v2).
- Storage S3 ya proviene del MVP.

### 13. Tarjeta NFC perdida o robada

**Política**: `flujo administrativo simple`.

- Supervisor o RH abre admin web → marca tarjeta `status = 'blocked'`.
- Sync inmediato al kiosko vía `device_command` (UID se rechaza en captura).
- Emisión de tarjeta nueva: nuevo registro en `nfc_card`, asignación a empleado.
- Sin auto-bloqueo por inactividad en MVP.

### 14. Empleado dado de baja: datos biométricos

**Política decidida**: `conserva indefinidamente, solo desactiva acceso`.

⚠️ **Bandera legal**: esta política **viola principio de finalidad de LFPDPPP** si no hay consentimiento explícito. **Recomendación fuerte**: evolucionar a *"Borrado tras periodo de retención (90 días)"* en v1.1, documentar en aviso de privacidad.

Implementación MVP: `employee.status = 'terminated'`, plantilla biométrica permanece pero se marca como inactiva.

Ver [10-cumplimiento-y-seguridad.md](./10-cumplimiento-y-seguridad.md).

### 15. Turno extendido (16+ horas seguidas)

**Política**: `permite siempre, marca tope de horas legal con alerta`.

- Sistema registra todas las horas.
- Cuando supera tope LFT (12 h efectivas/día como guideline), levanta alerta a supervisor.
- No bloquea checada (operación crítica continua).
- Cálculo paga lo que LFT obliga (HE 3x desde cierto punto).

### 16. Conflicto multi-kiosko (2 kioskos en misma planta capturan al mismo obrero)

**Política**: `registra ambos, marca para revisión del supervisor`.

- Sin deduplicación automática.
- Si llegan 2 eventos del mismo `(employee_id, type)` en ventana < 5 min desde kioskos distintos:
  - `review_queue.reason = 'multi_kiosko_conflict'`.
  - Ambos eventos quedan en `event_raw`.
- Supervisor resuelve: invalida uno, conserva el otro, agrega comentario.

## Detección automática del review queue

Job nocturno corre estas detecciones después del cálculo:

```sql
-- Multi-kiosko conflict
SELECT employee_id, event_type, plant_id, MIN(event_time), MAX(event_time)
FROM event_raw
WHERE event_time >= today_start AND is_valid
GROUP BY employee_id, event_type, plant_id, date_trunc('minute', event_time)
HAVING COUNT(DISTINCT kiosko_id) > 1
   AND MAX(event_time) - MIN(event_time) < interval '5 minutes';

-- Missing check_in (hay check_out pero no check_in)
-- Missing check_out (hay check_in pero pasó hora de cierre + 1h)
-- Extended shift (más de 12h efectivas en un día)
-- Out of window (eventos fuera de ±2h del turno asignado)
```

Cada detección genera entrada en `review_queue`. Idempotente: corre 2 veces, no duplica entradas.

## Flujo de corrección por supervisor

```
1. Supervisor entra a admin web → ve Review Queue de su planta.
2. Selecciona caso "missing_check_in para EMP-00123 el 2026-04-27".
3. Modal de corrección:
   - Acción: "Crear evento" / "Invalidar" / "Ajustar hora"
   - Tipo: check_in
   - Hora propuesta: editable (auto-sugerida según turno)
   - Motivo: campo obligatorio (mínimo 10 chars)
4. POST /api/v1/corrections con Idempotency-Key.
5. Backend:
   - Crea event_correction.
   - Inserta nuevo event_raw con capture_method='manual'.
   - Marca review_queue.status = 'resolved'.
   - Trigger de audit_log.
   - Encola recálculo del día.
6. Job recálculo corre en < 1 min (o on-demand).
7. Supervisor ve actualización en dashboard.
```

## Flujo de cierre de periodo

```
1. RH entra a admin web → Pay Periods → selecciona periodo abierto.
2. Sistema muestra preview:
   - Días incompletos por empleado (warnings).
   - Review queue pendiente del periodo (warnings).
   - Empleados con cero checadas (verificar si vacaciones o falta).
3. RH revisa, decide:
   - Resolver pendientes antes de cerrar.
   - O forzar cierre con flag (excepción audit-loggeada).
4. POST /pay-periods/{id}/close.
5. Backend:
   - Valida: si force=false y hay pendientes → 422.
   - Genera pay_period_summary por empleado.
   - Marca pay_period.status = 'closed'.
   - Audit log.
   - Genera CSV en S3 con pre-signed URL.
6. RH descarga CSV o lo importa a su nómina.
7. Tras N días (locked_after_days), pasa a 'locked'.
```

## Flujo de tenant onboarding (manual en MVP)

Ver [11-riesgos-y-runbooks.md](./11-riesgos-y-runbooks.md) para el runbook detallado.

```
1. SQL admin tool ejecuta:
   - INSERT tenant
   - INSERT app_user (primer admin del tenant)
   - INSERT plant(s) iniciales
   - INSERT shift(s) base
3. Email al admin con credenciales temporales (Cognito).
4. Admin inicia sesión, cambia password, asigna policy_profile a plantas.
5. RH del tenant carga empleados (CSV import).
6. RH genera/asigna PINs y/o tarjetas NFC.
7. Setup de kioskos en cada planta (ver runbook).
8. Periodo de prueba 1 semana antes de go-live formal.
```

## Diagrama de estados de un evento

```
[CAPTURED] ──┐
             │
             ▼
        [PENDING_SYNC] (kiosko local)
             │
             ▼
        [SYNCED] ────► [VALID] ─────► (incluido en cálculo)
                          │
                          ▼
                    [INVALIDATED] ──► (excluido del cálculo, audit log)
                          │
                          ▼
                    [ARCHIVED] (5+ años, S3 Glacier)
                          │
                          ▼
                    [PURGED] (post-retención)
```

## Anti-patrones que evitamos

1. **NO** modificar `event_raw` jamás. Las correcciones son nuevos registros.
2. **NO** calcular horas en captura. Solo persistir.
3. **NO** confiar en el reloj del cliente sin offset. Heartbeat lo corrige.
4. **NO** validaciones de negocio bloqueantes en captura (ej. "fuera de turno"). Solo warnings.
5. **NO** retornar 5xx por validación de negocio. Siempre 422 con código machine-readable.
6. **NO** hacer queries cross-tenant sin RLS desactivado explícitamente (super-admin paths).

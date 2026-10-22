# Motor de cálculo de horas

## Objetivo

Transformar eventos crudos en categorías de horas trabajadas según reglas de la **LFT mexicana** y la política configurada de la planta. Output: `attendance_day` listo para agregación en `pay_period_summary`.

## Marco legal LFT (no negociable)

| Concepto | Límite legal | Artículo LFT |
|---|---|---|
| Jornada diurna (6:00–20:00) | Máx 8 h/día | Art. 60-61 |
| Jornada nocturna (20:00–6:00) | Máx 7 h/día | Art. 60-61 |
| Jornada mixta (< 3.5 h nocturnas) | Máx 7.5 h/día | Art. 60-61 |
| Semana laboral | Máx 48 h con 1 día descanso pagado | Art. 59 |
| Horas extra | Máx 3 h/día, 3 días/semana (9 h sem) | Art. 66 |
| HE primeras 9 h/sem | **2x salario** | Art. 67 |
| HE arriba de 9 h/sem o > tope diario | **3x salario** | Art. 68 |
| Domingo trabajado (con descanso = domingo) | **+25% prima** | Art. 71 |
| Día descanso semanal trabajado | **3x** (salario + doble) | Art. 73 |
| Festivo oficial Art. 74 trabajado | **3x** | Art. 75 |

El sistema **debe** clasificar cada hora trabajada en alguna de estas cubetas. Eso es el output a nómina.

## Categorías de horas en `attendance_day`

```
hours_regular              ─ horas dentro de jornada diurna normal
hours_regular_night        ─ horas dentro de jornada nocturna normal
hours_overtime_2x          ─ HE pagadas al doble (primeras 9 h/sem)
hours_overtime_3x          ─ HE pagadas al triple (resto)
hours_holiday              ─ horas en festivo oficial trabajado (3x)
hours_sunday_premium       ─ horas en domingo con prima (+25%)
hours_rest_day_worked      ─ horas en día de descanso semanal (3x)
```

Una misma hora física puede contar simultáneamente en varias categorías (ej. domingo festivo trabajado en horas extra: cae en `hours_holiday` + cuenta para `total_ot_3x` semanal).

## Algoritmo de cálculo

```
Input:
  - employee_id
  - business_date
  - List<EventRaw> del día (todos válidos, ordenados por event_time)
  - Policy snapshot (JSON merge de policy_profile.config + plant.policy_override)
  - Shift asignado al empleado para business_date
  - Calendar (festivos)
  - Histórico semanal (días previos en la semana laboral para sumar HE)

Algoritmo:
  1. Validar y emparejar eventos
     - check_in / check_out forman pairs
     - lunch_out / lunch_in forman sub-intervalos a descontar
     - Si falta uno: marcar is_complete=false, no calcular horas
  
  2. Calcular tiempo efectivo
     - Suma intervalos check_in→check_out
     - Resta intervalos de comida (según política: auto_deduct, manual_punch, none)
  
  3. Aplicar tolerancia de retardo
     - Si llegó tarde > tolerance_minutes, marca is_late=true
     - Aplica política: descontar exacto / 30min bloque / 3-strikes
  
  4. Asignar día contable (business_date)
     - shift.crosses_midnight + policy.shift_day_assignment determina
  
  5. Clasificar tiempo en jornada normal vs extras
     - Compara con shift.legal_max_hours
     - Sobrante → HE (split entre 2x y 3x según semana)
  
  6. Marcar contexto del día
     - Es festivo? (holiday table) → hours_holiday
     - Es domingo? (date weekday + sunday_premium_policy) → hours_sunday_premium
     - Es día de descanso del empleado? (shift.rest_days) → hours_rest_day_worked
  
  7. Generar registro attendance_day
     - Persistir snapshot de policy en policy_snapshot
     - Calcular y persistir todas las hours_*
     - Set calculated_at, recalc_count++

Output:
  - 1 registro attendance_day
  - 0+ registros review_queue (warnings detectados)
```

## Estructura JSONB de la política

Esta es la pieza más importante. Toda configuración por planta vive aquí:

```json
{
  "schema_version": 1,
  "based_on_profile": "industrial_standard",
  
  "tolerance": {
    "late_grace_minutes": 10,
    "late_policy": "exact",
    "anti_double_tap_seconds": 30
  },
  
  "rounding_minutes": 0,
  
  "overtime": {
    "trigger": "flex_minutes",
    "flex_minutes": 15,
    "daily_cap_action": "register_3x",
    "weekly_cap_action": "register_3x",
    "require_authorization": false
  },
  
  "lunch": {
    "handling": "auto_deduct",
    "auto_deduct_minutes": 30
  },
  
  "shift_day_assignment": "entry_day",
  
  "sunday_premium_policy": "only_if_rest_is_sunday",
  
  "rest_day_policy": "follow_shift",
  
  "absence_bonus": {
    "enabled": false,
    "rules": null
  },
  
  "fallback_chain": ["nfc", "pin"],
  
  "photo_policy": "off",
  
  "encryption": {
    "events_pii_encrypted": true,
    "biometric_always_encrypted": true
  },
  
  "pay_period": {
    "type": "weekly",
    "anchor_day": "saturday",
    "lock_after_close_days": 3
  },
  
  "extended_shift": {
    "alert_after_hours": 12,
    "block_after_hours": null
  }
}
```

### Campos detallados

#### `tolerance`
- `late_grace_minutes` (int, default 10): minutos de gracia tras hora de entrada antes de marcar retardo.
- `late_policy` (enum):
  - `exact` — descuenta minutos exactos llegados tarde.
  - `block_30min` — descuenta 30 min por bloque o fracción.
  - `three_strikes` — 3 retardos = 1 falta, sin descuento de minutos.
- `anti_double_tap_seconds` (int, default 30): ventana para ignorar segundo evento del mismo tipo.

#### `rounding_minutes`
- 0 = sin redondeo (recomendado).
- 5 = redondea a 5 min.
- 15 = redondea a cuartos de hora (legacy).

#### `overtime`
- `trigger` (enum):
  - `strict_lft` — cualquier hora arriba del turno legal cuenta como HE.
  - `with_authorization` — HE solo si hay autorización previa registrada (no MVP).
  - `flex_minutes` — flex inicial sin contar HE, después se aplica.
- `flex_minutes` (int, default 15): si `trigger=flex_minutes`, los primeros N minutos extra no cuentan.
- `daily_cap_action`: qué hacer si rebasa 3 h extra/día.
  - `register_3x` — paga todo arriba a 3x (cumple LFT).
  - `block` — bloquea checada al rebasar.
  - `alert` — registra normal, alerta supervisor.
- `weekly_cap_action`: análogo para 9 h extra/semana.

#### `lunch`
- `handling`:
  - `auto_deduct` — resta automáticamente.
  - `manual_punch` — obrero checa salida y entrada.
  - `none` — comida fuera de jornada pagada.
- `auto_deduct_minutes` (int, default 30): minutos a descontar si `auto_deduct`.

#### `shift_day_assignment`
- `entry_day` — turno cuenta al día de la entrada.
- `exit_day` — al día de la salida.
- `split` — divide entre ambos días (cada hora a su día real).

#### `sunday_premium_policy`
- `all_who_work` — paga prima a cualquiera que trabaje domingo.
- `only_if_rest_is_sunday` — solo si su día de descanso ES domingo (estricto LFT).
- `off` — no aplica prima dominical.

#### `pay_period`
- `type`: `weekly`, `biweekly`, `fortnightly`, `monthly`.
- `anchor_day`: día de inicio para semanales/catorcenales (`monday`, `saturday`, etc.).
- `lock_after_close_days` (int, default 3): tras cierre, periodo se "locka" tras N días.

## Perfiles default

### "Industrial estándar"

```json
{
  "tolerance": { "late_grace_minutes": 10, "late_policy": "three_strikes" },
  "rounding_minutes": 0,
  "overtime": { "trigger": "flex_minutes", "flex_minutes": 15, "daily_cap_action": "register_3x", "weekly_cap_action": "register_3x" },
  "lunch": { "handling": "auto_deduct", "auto_deduct_minutes": 30 },
  "shift_day_assignment": "entry_day",
  "sunday_premium_policy": "only_if_rest_is_sunday",
  "fallback_chain": ["nfc", "pin"],
  "pay_period": { "type": "weekly", "anchor_day": "saturday", "lock_after_close_days": 3 }
}
```

Cubre: planta de manufactura típica, turnos diurnos rotativos, semana sábado-viernes.

### "Administrativo / Oficina"

```json
{
  "tolerance": { "late_grace_minutes": 15, "late_policy": "exact" },
  "rounding_minutes": 0,
  "overtime": { "trigger": "with_authorization", "daily_cap_action": "block", "weekly_cap_action": "block" },
  "lunch": { "handling": "manual_punch" },
  "shift_day_assignment": "entry_day",
  "sunday_premium_policy": "off",
  "fallback_chain": ["pin"],
  "pay_period": { "type": "biweekly", "anchor_day": "monday", "lock_after_close_days": 5 }
}
```

Cubre: personal administrativo, jornada típica 9-18, comida punteada, sin domingos.

## Casos especiales del cálculo

### Turno nocturno cruzando medianoche

```
Empleado entra lunes 22:00, sale martes 06:00.

shift.crosses_midnight = true
policy.shift_day_assignment = "entry_day"

Resultado:
  business_date = lunes
  Total horas = 8
  De ellas: 0 diurnas + 8 nocturnas (porque todas en franja 20:00-06:00)
  No HE (es la jornada normal nocturna de 7 h... wait, son 8 → 1 h HE!)
  
  Cálculo correcto:
    hours_regular_night = 7.0  (jornada legal nocturna)
    hours_overtime_2x   = 1.0  (la hora extra cae en 2x semanal)
```

### Domingo trabajado con prima

```
Empleado con descanso = domingo y sábado, entra domingo 8:00, sale 16:00 (8 h).

policy.sunday_premium_policy = "only_if_rest_is_sunday"

Resultado:
  hours_regular = 7.5  (jornada normal mixta o lo que aplique)
  hours_sunday_premium = 7.5  (mismas horas marcadas para prima 25%)
  hours_rest_day_worked = 7.5  (porque domingo es día de descanso → 3x)
  
  Output a nómina: las 7.5 h se pagan al 3x + se agrega 25% prima dominical.
```

### Festivo trabajado

```
Empleado trabaja el 1 de mayo (festivo Art. 74 LFT).

holiday.type = 'lft_official'
holiday.pay_multiplier = 3.0

Resultado:
  hours_holiday = horas trabajadas
  No se duplica con hours_regular (festivo "consume" la categorización).
```

### HE 2x vs 3x (split semanal)

```
Empleado en una semana acumuló:
  Lun:  8 h normales + 2 h extra
  Mar:  8 h normales + 3 h extra
  Mié:  8 h normales + 3 h extra
  Jue:  8 h normales + 4 h extra (¡rebasa tope diario!)
  Vie:  8 h normales
  Sáb:  descanso

Total HE de la semana: 12 h.

Cálculo según LFT:
  HE 2x (primeras 9 h/sem): 9 h
  HE 3x (resto): 3 h
  
  Pero el jueves rebasó tope diario (3 h máx), así que la 4ta hora de jueves
  va a 3x aunque la cuenta semanal aún no hubiera llegado a 9.
  
  Por orden cronológico:
    Lun 2 h → 2x   (acum: 2)
    Mar 3 h → 2x   (acum: 5)
    Mié 3 h → 2x   (acum: 8)
    Jue 3 h → 1 h a 2x (acum: 9), 2 h a 3x (rebasó tope diario y semanal)
    Vie 0 h
  
  Total semanal: 2+3+3+1 = 9 h al 2x; 2+1 = 3 h al 3x. (Total 12 h extra.)
```

Esta lógica vive en el motor de cálculo, no en el cliente.

## Reproducibilidad y recálculo

Cada `attendance_day` guarda en `policy_snapshot` el JSON completo de política aplicada. Eso permite:

1. **Recálculo idempotente**: misma entrada + misma política = mismo output.
2. **Recálculo histórico tras cambio de política**: usa la versión vigente en ese día (`plant_policy_history`), no la actual.
3. **Auditoría**: si alguien duda del resultado, se puede reproducir.

```
POST /api/v1/attendance/recalculate
Body: { "scope": "employee", "scope_id": "EMP-123", "from_date": "...", "to_date": "..." }

→ Encola job que itera días, lee policy efectiva por día, recalcula, persiste.
→ recalc_count++ y nuevo policy_snapshot si la política cambió.
```

## Decisiones de implementación

### Job nocturno vs on-demand

- **Nocturno** (default): cron 3 AM zona horaria del tenant, recalcula días pendientes (sin `attendance_day`) y días con `recalc_pending=true`.
- **On-demand**: tras correcciones, marca `recalc_pending=true` en `attendance_day` afectados; job processes en próximo minuto.

### Eventos en orden vs fuera de orden

- Backend recibe eventos en cualquier orden.
- Cálculo ordena por `event_time` antes de procesar.
- Si un evento llega tarde (sync offline después de 3 días), `recalc_pending=true` para su `business_date`.

### Ausentismo

- Si el día contable transcurrió y no hay eventos: `is_absent = true` por default.
- Marcar como ausencia justificada (vacaciones, incapacidad) requiere intervención manual de RH (en MVP). v1.1: módulo de permisos.

### Horas mínimas pagables

- Sin minimum de jornada en MVP. Si un obrero entra y sale 10 min después, `hours_regular = 0.17`.
- Si esto causa fricción operativa, agregar a política `policy.minimum_pay_hours_per_event`.

## Output a nómina (CSV genérico)

Columnas estándar:

```csv
employee_number,full_name,plant_code,period_start,period_end,
hours_regular,hours_regular_night,hours_overtime_2x,hours_overtime_3x,
hours_holiday,hours_sunday_premium,hours_rest_day_worked,
total_lates,total_absences
```

Ejemplo:
```csv
EMP-00123,"María González",PLANTA-NORTE,2026-04-20,2026-04-26,
40.00,0.00,2.50,1.00,
0.00,0.00,0.00,
2,0
```

Plugin system permite v2 generar formatos específicos (CONTPAQi, NOI, SAP) sin tocar core. Ver [ADR-005](./adrs/ADR-005-strategy-pattern-extensibilidad.md).

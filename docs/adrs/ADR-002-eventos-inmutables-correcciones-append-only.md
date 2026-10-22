# ADR-002: Eventos crudos inmutables; correcciones como nuevos registros (append-only)

**Estado**: Aceptado
**Fecha**: 2026-04-26
**Decisores**: Tech Lead

## Contexto

Los eventos de asistencia (`event_raw`) son la fuente de verdad legal del sistema. Cualquier modificación a un evento (corrección por supervisor, ajuste por error, invalidación) genera trazabilidad exigible legalmente:

- Por LFT Art. 804: registros laborales conservables 5 años.
- Por NOM-151: integridad de mensajes de datos.
- Por LFPDPPP: auditoría de tratamiento de datos personales.

Un sistema que permite UPDATE directo sobre eventos pierde estas garantías.

## Decisión

`event_raw` es **append-only**: nunca `UPDATE`, nunca `DELETE`. Las correcciones se modelan así:

1. **Invalidar**: marcar el evento original con `is_valid = false`. NO modifica los datos del evento, solo cambia un flag de "incluir en cálculo o no". El registro queda con motivo y autor de invalidación.
2. **Crear**: insertar un nuevo `event_raw` con `capture_method = 'manual'`, `recorded_by_user_id` poblado.
3. **Ajustar tiempo o tipo**: ejecuta paso 1 (invalidar original) + paso 2 (insertar nuevo con datos correctos).

Cada operación de corrección crea un registro en `event_correction` que apunta al evento original y al evento resultante (si aplica).

## Razonamiento

### Por qué append-only y no UPDATE con audit log

Una alternativa común es UPDATE + audit log que registra el `before` y `after`. Eso funciona pero:

1. **No es defensible legalmente del mismo modo**: si alguien manipula la tabla `event_raw` saltándose la app, no queda traza. Append-only hace que el dato original siempre exista.
2. **Reproducibilidad**: cualquier cálculo histórico puede reproducirse seleccionando solo los eventos `is_valid=true` antes de cierta fecha.
3. **Simplicidad mental**: "el evento siempre fue X, después se invalidó" es más claro que "el evento fue X y ahora es Y".
4. **Idempotencia natural**: si un cliente reintenta un POST de evento con el mismo UUID, simplemente no inserta nada. UPDATE-based requiere lógica especial.

### Costo de la decisión

- Tabla `event_raw` crece más rápido (no hay reducción por updates).
- Queries deben filtrar `is_valid=true` siempre. Mitigado con índices condicionales y/o vista `event_valid`.
- Correcciones múltiples al mismo "evento lógico" generan cadena de invalidaciones; trazable pero requiere UI clara.

### Implementación específica

```sql
-- Esquema event_raw con flag de invalidación
CREATE TABLE event_raw (
    id UUID PRIMARY KEY,                        -- UUID v7 cliente
    tenant_id UUID NOT NULL,
    ...
    is_valid BOOLEAN NOT NULL DEFAULT true,
    invalidated_by UUID REFERENCES app_user(id),
    invalidated_reason TEXT,
    invalidated_at TIMESTAMPTZ,
    recorded_by_user_id UUID REFERENCES app_user(id)  -- solo para 'manual'
);

-- NO triggers de UPDATE/DELETE en event_raw — la app no debería usarlos.
-- Solo permitido UPDATE de los flags de invalidación (los demás campos están protegidos).

-- Tabla de correcciones con referencia bidireccional
CREATE TABLE event_correction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    event_raw_id UUID REFERENCES event_raw(id),     -- el original (NULL si action='create')
    action TEXT,
    new_event_time TIMESTAMPTZ,
    new_event_type TEXT,
    reason TEXT NOT NULL,
    authorized_by UUID NOT NULL REFERENCES app_user(id),
    authorized_at TIMESTAMPTZ,
    resulting_event_id UUID REFERENCES event_raw(id) -- el nuevo evento (si action != 'invalidate')
);
```

### Flujo en código

```java
@Service
public class EventCorrectionService {
    
    public CorrectionResult applyCorrection(CorrectionRequest req) {
        switch (req.action) {
            case CREATE:
                EventRaw newEvent = createManualEvent(req);
                EventCorrection corr = recordCorrection(null, newEvent.id, req);
                triggerRecalculation(newEvent.businessDate);
                return new CorrectionResult(corr.id, newEvent.id);
                
            case INVALIDATE:
                EventRaw original = findEvent(req.eventRawId);
                invalidateEvent(original, req.reason, currentUser());
                EventCorrection corr = recordCorrection(original.id, null, req);
                triggerRecalculation(original.businessDate);
                return new CorrectionResult(corr.id, null);
                
            case ADJUST_TIME:
            case ADJUST_TYPE:
                EventRaw original = findEvent(req.eventRawId);
                invalidateEvent(original, req.reason, currentUser());
                EventRaw newEvent = createAdjustedEvent(original, req);
                EventCorrection corr = recordCorrection(original.id, newEvent.id, req);
                triggerRecalculation(original.businessDate);
                return new CorrectionResult(corr.id, newEvent.id);
        }
    }
}
```

## Consecuencias

### Positivas

- Auditoría perfecta: nunca hay datos perdidos.
- Reproducibilidad legal del cálculo.
- Idempotencia natural sin tabla extra.
- Defensa contra disputas: el original siempre existe.

### Negativas

- Storage crece (~5-10% más vs UPDATE).
- Queries de cálculo deben filtrar por `is_valid`.
- UI debe mostrar claramente cuando un evento fue invalidado/reemplazado.

### Métricas de éxito

- 100% de modificaciones a eventos generan registro en `event_correction`.
- 0 UPDATEs/DELETEs sobre `event_raw` fuera de los flags de invalidación.
- Test golden: aplicar 50 correcciones, recalcular el día, output coincide con cálculo manual de QA.

## Alternativas rechazadas

- **UPDATE directo + audit log**: pierde defensibilidad legal y reproducibilidad.
- **Versioning con `version` column**: complica queries de cálculo y duplica datos sin claridad de "cuál es el actual".
- **Event sourcing puro**: overkill. La fuente de verdad es la tabla, no el log de eventos.

## Re-evaluar cuando

- Storage de `event_raw` crezca > 10 GB y cause issues de performance — entonces partitioning por año.
- Cliente requiera "borrar eventos definitivamente" (LFPDPPP cancelación) — implementar borrado físico tras el plazo de retención LFT.

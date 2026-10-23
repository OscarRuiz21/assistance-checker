-- Fase 3.3 (doc 03-modelo-datos.md §tenant / "Estrategia de retención"):
-- config de retención por tenant + tabla de archivo para event_raw + función
-- de archivado-y-purga usada por el job @Scheduled (ver
-- com.assistance.compliance.internal.service.RetentionService, deshabilitado
-- por defecto con app.retention.enabled=false).
--
-- LFT Art. 804 exige conservar los registros de asistencia; "retención" aquí
-- NUNCA significa DELETE puro: los eventos viejos se copian primero a
-- event_raw_archive (misma forma de fila + archived_at) y solo entonces se
-- borran de la tabla operativa event_raw, dentro de la misma transacción de
-- archive_and_purge_events(...).

ALTER TABLE tenant
    ADD COLUMN retention_config JSONB NOT NULL
        DEFAULT '{"events_years":5,"audit_years":7}'::jsonb;

-- Misma forma que event_raw (incluidas las columnas de invalidación de V4),
-- más archived_at. Sin FKs a employee/plant/kiosko: estos eventos pueden
-- sobrevivir al borrado lógico de sus referencias años después.
CREATE TABLE event_raw_archive (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plant_id UUID NOT NULL,
    kiosko_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    capture_method TEXT NOT NULL,
    device_time TIMESTAMPTZ NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    nfc_card_id UUID,
    is_valid BOOLEAN NOT NULL,
    invalidated_by UUID,
    invalidated_reason TEXT,
    invalidated_at TIMESTAMPTZ,
    recorded_by_user_id UUID,
    raw_payload JSONB,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_archive_employee_time ON event_raw_archive(tenant_id, employee_id, event_time);
CREATE INDEX idx_event_archive_tenant_time ON event_raw_archive(tenant_id, event_time);

ALTER TABLE event_raw_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_raw_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON event_raw_archive
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Append-only igual que event_raw/event_correction/audit_log: el archivo es
-- en sí mismo un registro legal, no se edita.
REVOKE UPDATE, DELETE ON event_raw_archive FROM ${app_db_user};

CREATE OR REPLACE FUNCTION event_raw_archive_append_only_guard()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'event_raw_archive es append-only: no se permite UPDATE ni DELETE';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_raw_archive_append_only
    BEFORE UPDATE OR DELETE ON event_raw_archive
    FOR EACH ROW EXECUTE FUNCTION event_raw_archive_append_only_guard();

-- Archiva (INSERT en event_raw_archive) y purga (DELETE de event_raw) los
-- eventos de un tenant anteriores a p_cutoff, en una sola transacción:
-- SECURITY DEFINER (dueño = rol de migración, superusuario) para poder
-- hacer el DELETE sin que el rol de app necesite GRANT DELETE sobre
-- event_raw (nunca lo tiene, ver V3/V4) y para poder saltar el guard
-- append-only de V4 vía el mismo GUC app.bypass_append_only usado por
-- demo_reset_events, solo dentro de esta transacción (SET LOCAL).
--
-- Idempotente/seguro ante reintentos: si el job corre dos veces sobre el
-- mismo rango, el segundo INSERT ... SELECT no encuentra filas (ya se
-- borraron) y no hace nada.
CREATE OR REPLACE FUNCTION archive_and_purge_events(p_tenant_id UUID, p_cutoff TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_archived INTEGER;
BEGIN
    INSERT INTO event_raw_archive (
        id, tenant_id, plant_id, kiosko_id, employee_id, event_type, capture_method,
        device_time, event_time, received_at, nfc_card_id, is_valid,
        invalidated_by, invalidated_reason, invalidated_at, recorded_by_user_id,
        raw_payload, archived_at
    )
    SELECT
        id, tenant_id, plant_id, kiosko_id, employee_id, event_type, capture_method,
        device_time, event_time, received_at, nfc_card_id, is_valid,
        invalidated_by, invalidated_reason, invalidated_at, recorded_by_user_id,
        raw_payload, now()
    FROM event_raw
    WHERE tenant_id = p_tenant_id
      AND event_time < p_cutoff;
    GET DIAGNOSTICS v_archived = ROW_COUNT;

    IF v_archived = 0 THEN
        RETURN 0;
    END IF;

    PERFORM set_config('app.bypass_append_only', 'on', true); -- true = solo transacción actual
    DELETE FROM event_raw
    WHERE tenant_id = p_tenant_id
      AND event_time < p_cutoff;

    RETURN v_archived;
END;
$$;

REVOKE ALL ON FUNCTION archive_and_purge_events(UUID, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION archive_and_purge_events(UUID, TIMESTAMPTZ) TO ${app_db_user};

-- Lista todos los tenants activos con su retención de eventos configurada,
-- para que el job nocturno pueda iterar tenant por tenant sin que el rol de
-- app necesite "ver todos los tenants a la vez" (RLS fail-closed de V3
-- impediría un SELECT * FROM tenant sin filtro). SECURITY DEFINER con dueño
-- superusuario: Postgres exime siempre a los superusuarios de RLS
-- (independiente de FORCE ROW LEVEL SECURITY, que solo afecta al dueño de
-- tabla cuando NO es superusuario), así que esta función sí puede cruzar
-- tenants deliberadamente — es la única vía diseñada para hacerlo.
CREATE OR REPLACE FUNCTION list_tenant_ids_for_retention()
RETURNS TABLE(tenant_id UUID, events_years INT)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    -- La tabla tenant del esquema demo (V1) no tiene columna deleted_at
    -- (soft-delete de tenant no existe en este alcance); cuando se agregue
    -- en una migración futura, añadir aquí el filtro WHERE deleted_at IS NULL.
    SELECT id, COALESCE((retention_config->>'events_years')::int, 5)
    FROM tenant;
$$;

REVOKE ALL ON FUNCTION list_tenant_ids_for_retention() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_tenant_ids_for_retention() TO ${app_db_user};

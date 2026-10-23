-- Fase 3.7 (doc 03-modelo-datos.md §6): tablas de corrección y cola de
-- revisión. event_correction es append-only (igual espíritu que event_raw:
-- es el registro legal de qué corrección se autorizó y por qué); review_queue
-- es mutable (transiciones de status pending -> resolved/dismissed).

CREATE TABLE event_correction (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    event_raw_id UUID REFERENCES event_raw(id),
    action TEXT NOT NULL CHECK (action IN ('create','invalidate','adjust_time','adjust_type')),
    new_event_time TIMESTAMPTZ,
    new_event_type TEXT,
    reason TEXT NOT NULL,
    authorized_by UUID NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resulting_event_id UUID REFERENCES event_raw(id)
);

CREATE INDEX idx_correction_event ON event_correction(event_raw_id);
CREATE INDEX idx_correction_tenant_time ON event_correction(tenant_id, authorized_at DESC);

ALTER TABLE event_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON event_correction
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Append-only: ni siquiera flags de invalidación aquí, es en sí mismo un
-- registro de auditoría de corrección. Solo INSERT/SELECT para la app.
REVOKE UPDATE, DELETE ON event_correction FROM ${app_db_user};

CREATE OR REPLACE FUNCTION event_correction_append_only_guard()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'event_correction es append-only: no se permite UPDATE ni DELETE';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_correction_append_only
    BEFORE UPDATE OR DELETE ON event_correction
    FOR EACH ROW EXECUTE FUNCTION event_correction_append_only_guard();

-- Cola de revisión de anomalías (conflictos multi-kiosko, turnos sin
-- check-out, etc.). Mutable: sí se permite UPDATE para cambiar status.
CREATE TABLE review_queue (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    employee_id UUID REFERENCES employee(id),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT NOT NULL CHECK (reason IN (
        'multi_kiosko_conflict','missing_check_in','missing_check_out',
        'extended_shift','out_of_window','other'
    )),
    related_event_ids UUID[] NOT NULL DEFAULT '{}',
    status TEXT NOT NULL CHECK (status IN ('pending','resolved','dismissed')) DEFAULT 'pending',
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    resolution TEXT
);

CREATE INDEX idx_review_pending ON review_queue(tenant_id, plant_id, status, detected_at)
    WHERE status = 'pending';

ALTER TABLE review_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON review_queue
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

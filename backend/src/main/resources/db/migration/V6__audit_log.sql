-- Fase 3.2 (doc 03-modelo-datos.md §8): audit_log transversal + trigger
-- write_audit_log() para las tablas mutables bajo alcance de este agente
-- (employee, nfc_card, kiosko, event_correction). Las exportaciones/lecturas
-- sensibles que no son un INSERT/UPDATE/DELETE de fila (export CSV, lectura
-- puntual de PII) se registran explícitamente vía compliance::api AuditApi
-- (AuditApiImpl ya inserta directamente en esta tabla con JdbcTemplate).
--
-- Igual que event_raw/event_correction, audit_log es append-only desde el
-- día uno: ni un auditor con acceso a la app debería poder editar el
-- historial. Se refuerza con REVOKE explícito (el rol de app ya tendría
-- UPDATE/DELETE por los privilegios por defecto otorgados en V3).

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_user_id UUID,
    actor_kiosko_id UUID,
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id UUID,
    before JSONB,
    after JSONB,
    reason TEXT,
    ip_address INET,
    user_agent TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log(tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit_log(tenant_id, actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_time ON audit_log(occurred_at);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Append-only: solo SELECT + INSERT para el rol de app (ya los tiene por los
-- privilegios por defecto de V3); se revocan explícitamente UPDATE/DELETE.
REVOKE UPDATE, DELETE ON audit_log FROM ${app_db_user};

CREATE OR REPLACE FUNCTION audit_log_append_only_guard()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log es append-only: no se permite UPDATE ni DELETE';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only_guard();

-- ─── Trigger transversal para tablas de negocio mutables ───────────────────
-- Lee los GUCs de sesión que ya fija TenantAwareDataSource en cada checkout
-- de conexión (app.tenant_id / app.actor_user_id / app.actor_kiosko_id).
-- No requiere SECURITY DEFINER: el rol de app ya tiene INSERT sobre
-- audit_log (privilegios por defecto de V3, no revocados arriba).
CREATE OR REPLACE FUNCTION write_audit_log()
RETURNS TRIGGER AS $$
DECLARE
    v_actor_user_id UUID;
    v_actor_kiosko_id UUID;
    v_tenant_id UUID;
BEGIN
    v_tenant_id := NULLIF(current_setting('app.tenant_id', true), '')::uuid;
    v_actor_user_id := NULLIF(current_setting('app.actor_user_id', true), '')::uuid;
    v_actor_kiosko_id := NULLIF(current_setting('app.actor_kiosko_id', true), '')::uuid;

    -- Si no hay tenant en el GUC (p. ej. una conexión de herramienta admin
    -- fuera del flujo normal de la app), se usa el tenant_id de la propia
    -- fila para no perder el registro de auditoría.
    IF v_tenant_id IS NULL THEN
        v_tenant_id := COALESCE(NEW.tenant_id, OLD.tenant_id);
    END IF;

    INSERT INTO audit_log (
        tenant_id, actor_user_id, actor_kiosko_id, action,
        entity_type, entity_id,
        before, after,
        occurred_at
    ) VALUES (
        v_tenant_id,
        v_actor_user_id,
        v_actor_kiosko_id,
        TG_OP,
        TG_TABLE_NAME,
        COALESCE(NEW.id, OLD.id),
        CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN to_jsonb(OLD) ELSE NULL END,
        CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN to_jsonb(NEW) ELSE NULL END,
        now()
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Tablas bajo alcance de este agente (employee, device::nfc_card/kiosko,
-- attendance::event_correction). plant/shift/policy_profile/etc. quedan
-- para cuando esos módulos existan (fuera de Fases 2-3).
CREATE TRIGGER trg_audit_employee
    AFTER INSERT OR UPDATE OR DELETE ON employee
    FOR EACH ROW EXECUTE FUNCTION write_audit_log();

CREATE TRIGGER trg_audit_nfc_card
    AFTER INSERT OR UPDATE OR DELETE ON nfc_card
    FOR EACH ROW EXECUTE FUNCTION write_audit_log();

CREATE TRIGGER trg_audit_kiosko
    AFTER INSERT OR UPDATE OR DELETE ON kiosko
    FOR EACH ROW EXECUTE FUNCTION write_audit_log();

-- event_correction es en sí mismo append-only (ver V5: BEFORE UPDATE/DELETE
-- siempre lanza excepción), así que en la práctica este trigger solo verá
-- INSERT — se agrega igual por completitud/consistencia con el resto de
-- tablas sensibles y porque el propio INSERT en event_correction es un
-- evento que un auditor/DPO debe poder reconstruir desde audit_log también.
CREATE TRIGGER trg_audit_event_correction
    AFTER INSERT OR UPDATE OR DELETE ON event_correction
    FOR EACH ROW EXECUTE FUNCTION write_audit_log();

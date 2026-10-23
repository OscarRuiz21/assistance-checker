-- Fase 2.1/2.2: rol de aplicación sin privilegios de superusuario + Row-Level
-- Security en todas las tablas existentes con tenant_id (ADR-001, capa 3 de
-- defensa). El backend (bean "dataSource", ver
-- com.assistance.shared.internal.tenancy.TenantAwareDataSource) hace
-- set_config('app.tenant_id', ...) en cada conexión; si no hay tenant en
-- contexto, el valor queda NULL y las políticas de abajo no matchean
-- ninguna fila (fail-closed).
--
-- Rol idempotente: puede re-ejecutarse (p. ej. en un entorno donde el rol ya
-- exista de una corrida anterior) sin fallar.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_db_user}') THEN
        EXECUTE format(
            'CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L',
            '${app_db_user}', '${app_db_password}'
        );
    ELSE
        -- Si el rol ya existe (p. ej. se re-crea la BD pero el rol vive en
        -- otro cluster/volúmen persistente), igual aseguramos la contraseña
        -- y los flags de seguridad correctos.
        EXECUTE format(
            'ALTER ROLE %I NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD %L',
            '${app_db_user}', '${app_db_password}'
        );
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${app_db_user};

-- event_raw es la excepción: append-only desde V4, por eso aquí solo recibe
-- SELECT + INSERT (nunca UPDATE/DELETE, ni ahora ni por defecto más
-- adelante — ver REVOKE explícito también en V4 por claridad/documentación).
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant, plant, employee, nfc_card, kiosko, kiosko_web
    TO ${app_db_user};
GRANT SELECT, INSERT ON event_raw TO ${app_db_user};

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_db_user};

-- Privilegios por defecto para objetos que creen migraciones futuras
-- (ejecutadas por el mismo rol de migración que corre este script). Cada
-- migración posterior que necesite restringir más (p. ej. audit_log
-- append-only) hace su propio REVOKE explícito después del CREATE TABLE.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_db_user};

-- ─── Row-Level Security ──────────────────────────────────────────────────
-- tenant: la "fila propia" se identifica por id, no por una columna
-- tenant_id separada.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE plant ENABLE ROW LEVEL SECURITY;
ALTER TABLE plant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plant
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE nfc_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE nfc_card FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nfc_card
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE kiosko ENABLE ROW LEVEL SECURITY;
ALTER TABLE kiosko FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kiosko
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE kiosko_web ENABLE ROW LEVEL SECURITY;
ALTER TABLE kiosko_web FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kiosko_web
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE event_raw ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_raw FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON event_raw
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

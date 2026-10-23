-- Plantas a las que un usuario tiene acceso explícito. Fila ausente para un
-- usuario con role tenant_admin/super_admin => acceso a TODAS las plantas del
-- tenant (ver TenantScope.canAccessPlant / plantIds vacío = todas). Para
-- rh/supervisor, ausencia de filas significa "sin plantas asignadas".
CREATE TABLE user_plant_access (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    plant_id UUID NOT NULL REFERENCES plant(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, plant_id)
);

CREATE INDEX idx_user_plant_access_user ON user_plant_access(user_id);

ALTER TABLE user_plant_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_plant_access FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_plant_access
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON user_plant_access TO ${app_db_user};

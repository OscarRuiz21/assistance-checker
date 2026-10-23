-- Usuarios admin (tenant_admin/rh/supervisor/super_admin). password_hash es
-- nullable porque en prod el usuario puede autenticarse solo contra Cognito
-- (cognito_sub) sin password local.
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    email TEXT NOT NULL,
    password_hash TEXT,
    cognito_sub TEXT,
    role TEXT NOT NULL CHECK (role IN ('super_admin','tenant_admin','rh','supervisor')),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);

-- RLS normal para todo acceso autenticado (ya con tenant conocido)...
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_user
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ...EXCEPCIÓN documentada (ver docs/16-readiness-plan.md): el login necesita
-- resolver el usuario por email ANTES de conocer el tenant (el email es la
-- única entrada del formulario). En vez de desactivar RLS en la tabla,
-- exponemos una función SECURITY DEFINER mínima que hace exactamente ese
-- lookup puntual y nada más; el resto de accesos a app_user pasan por RLS
-- normal una vez que TenantContext ya está poblado.
CREATE FUNCTION find_app_user_by_email(p_email TEXT)
RETURNS app_user
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
STABLE
AS $$
    SELECT * FROM app_user WHERE email = p_email AND status = 'active' LIMIT 1;
$$;

REVOKE ALL ON FUNCTION find_app_user_by_email(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION find_app_user_by_email(TEXT) TO ${app_db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON app_user TO ${app_db_user};

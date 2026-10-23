-- Token opaco de pairing del kiosko. Solo se persiste su sha256; el valor en
-- claro se entrega una única vez en la respuesta de /api/auth/kiosk/pair.
CREATE TABLE kiosko_device_token (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    kiosko_id UUID NOT NULL REFERENCES kiosko(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_kiosko_device_token_kiosko ON kiosko_device_token(kiosko_id);

-- EXCEPCIÓN DOCUMENTADA (sin RLS, igual que app_user/find_app_user_by_email):
-- el filtro de autenticación de kiosko recibe un header
-- "Authorization: Kiosk <token>" y debe resolver el tenant_id A PARTIR del
-- hash del token — no puede haber un `SET app.tenant_id` previo porque
-- todavía no se conoce el tenant. El lookup es siempre por `token_hash`
-- (columna UNIQUE, índice puntual), nunca un "dame todos los tokens de mi
-- tenant" sin filtrar, así que el riesgo de fuga cross-tenant es el mismo
-- que el de cualquier índice único normal. Una vez resuelta la fila, el
-- tenant_id de ESA fila es la fuente de verdad para poblar TenantScope.
GRANT SELECT, INSERT, UPDATE, DELETE ON kiosko_device_token TO ${app_db_user};

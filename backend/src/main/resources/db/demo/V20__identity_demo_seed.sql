-- Seed de identidad para el demo (solo perfiles local/demo — ver
-- spring.flyway.locations en application-local.yml / application-demo.yml).
--
-- Credenciales de demo (documentadas también en el reporte del agente, NO en
-- README público):
--   admin@demo.mx / Demo1234!      (role tenant_admin, tenant demo)
--   Token de kiosko pre-pareado para el kiosko demo eeee0001-...-000000000001:
--     DEMO-KIOSK-TOKEN-PLEASE-CHANGE-0001
--   (el kiosko web de la demo puede usarlo directo para no tener que pasar
--   por /k/pair en cada reset de entorno local)

INSERT INTO app_user (id, tenant_id, email, password_hash, role, status)
VALUES (
    'aaaa0001-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'admin@demo.mx',
    crypt('Demo1234!', gen_salt('bf')),
    'tenant_admin',
    'active'
);

-- tenant_admin no necesita filas en user_plant_access (plantIds vacío = todas
-- las plantas del tenant, ver TenantScope.canAccessPlant).

INSERT INTO kiosko_device_token (id, tenant_id, kiosko_id, token_hash, created_at, last_seen_at)
VALUES (
    'bbbb0001-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'eeee0001-0000-0000-0000-000000000001',
    encode(digest('DEMO-KIOSK-TOKEN-PLEASE-CHANGE-0001', 'sha256'), 'hex'),
    now(),
    NULL
);

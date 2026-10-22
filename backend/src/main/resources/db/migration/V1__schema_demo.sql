-- Schema demo. Subset del modelo de producción (ver docs/03-modelo-datos.md).
-- Se omite RLS, audit_log, motor de cálculo y modelo de pay_period — fuera de alcance del demo MVP.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenancy y usuarios admin
CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    rfc TEXT,
    contact_email TEXT,
    timezone TEXT NOT NULL DEFAULT 'America/Mexico_City',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Organización
CREATE TABLE plant (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name TEXT NOT NULL,
    code TEXT NOT NULL,
    address TEXT,
    timezone TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE employee (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    employee_number TEXT NOT NULL,
    full_name TEXT NOT NULL,
    curp TEXT,
    rfc TEXT,
    nss TEXT,
    default_plant_id UUID REFERENCES plant(id),
    pin_hash TEXT,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','on_leave','terminated')),
    hire_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, employee_number)
);

CREATE INDEX idx_employee_tenant_status ON employee(tenant_id, status);
CREATE INDEX idx_employee_default_plant ON employee(default_plant_id) WHERE status = 'active';

-- Métodos de captura
CREATE TABLE nfc_card (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    uid TEXT NOT NULL,
    employee_id UUID REFERENCES employee(id),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','blocked','lost')),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    blocked_at TIMESTAMPTZ,
    blocked_reason TEXT,
    UNIQUE (tenant_id, uid)
);

CREATE INDEX idx_nfc_card_active_uid ON nfc_card(tenant_id, uid) WHERE status = 'active';

-- Dispositivos (base + subtabla web kiosko, por consistencia con el modelo prod)
CREATE TABLE kiosko (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('zkteco_k50','web_kiosko')),
    enabled_methods TEXT[] NOT NULL DEFAULT ARRAY['nfc','pin'],
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive','retired')),
    last_heartbeat_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    app_version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kiosko_web (
    kiosko_id UUID PRIMARY KEY REFERENCES kiosko(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL,
    pairing_token_hash TEXT,
    pairing_expires_at TIMESTAMPTZ,
    paired_at TIMESTAMPTZ,
    user_agent TEXT,
    last_session_at TIMESTAMPTZ
);

-- Eventos (núcleo): append-only, simplified para demo
CREATE TABLE event_raw (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plant_id UUID NOT NULL REFERENCES plant(id),
    kiosko_id UUID NOT NULL REFERENCES kiosko(id),
    employee_id UUID NOT NULL REFERENCES employee(id),
    event_type TEXT NOT NULL CHECK (event_type IN ('check_in','check_out','lunch_out','lunch_in')),
    capture_method TEXT NOT NULL CHECK (capture_method IN ('nfc','pin','manual')),
    device_time TIMESTAMPTZ NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    nfc_card_id UUID REFERENCES nfc_card(id),
    is_valid BOOLEAN NOT NULL DEFAULT true,
    raw_payload JSONB
);

CREATE INDEX idx_event_employee_time ON event_raw(tenant_id, employee_id, event_time DESC);
CREATE INDEX idx_event_plant_time ON event_raw(tenant_id, plant_id, event_time DESC);
CREATE INDEX idx_event_received ON event_raw(received_at DESC);

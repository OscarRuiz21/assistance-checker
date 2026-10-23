-- Fase 3.7 (ADR-002): event_raw append-only reforzado a nivel de base de
-- datos, no solo por convención de la app. Nunca se otorgó UPDATE/DELETE al
-- rol de aplicación (ver V3), pero este trigger es la segunda capa: protege
-- incluso contra un rol con más privilegios (p. ej. un futuro rol de
-- soporte) y documenta explícitamente qué está permitido.

ALTER TABLE event_raw
    ADD COLUMN invalidated_by UUID,
    ADD COLUMN invalidated_reason TEXT,
    ADD COLUMN invalidated_at TIMESTAMPTZ,
    ADD COLUMN recorded_by_user_id UUID;

COMMENT ON COLUMN event_raw.invalidated_by IS
    'app_user.id de quien invalidó el evento. Sin FK a app_user: ese módulo '
    'puede no existir en todos los entornos de desarrollo de este repo en '
    'este punto del historial; se agrega la FK cuando ambos módulos converjan.';

-- GUC de sesión usada exclusivamente por funciones SECURITY DEFINER internas
-- (invalidate_event_raw NO la necesita porque solo toca columnas permitidas;
-- demo_reset_events y archive_and_purge_events SÍ la necesitan para poder
-- hacer DELETE). Nunca se expone una forma de setearla desde fuera de esas
-- funciones.
CREATE OR REPLACE FUNCTION event_raw_append_only_guard()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_setting('app.bypass_append_only', true) = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'event_raw es append-only: DELETE no permitido (use demo_reset_events '
            'o el job de retención/archivado)';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.plant_id IS DISTINCT FROM OLD.plant_id
            OR NEW.kiosko_id IS DISTINCT FROM OLD.kiosko_id
            OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
            OR NEW.event_type IS DISTINCT FROM OLD.event_type
            OR NEW.capture_method IS DISTINCT FROM OLD.capture_method
            OR NEW.device_time IS DISTINCT FROM OLD.device_time
            OR NEW.event_time IS DISTINCT FROM OLD.event_time
            OR NEW.received_at IS DISTINCT FROM OLD.received_at
            OR NEW.nfc_card_id IS DISTINCT FROM OLD.nfc_card_id
            OR NEW.raw_payload IS DISTINCT FROM OLD.raw_payload
            OR NEW.recorded_by_user_id IS DISTINCT FROM OLD.recorded_by_user_id
        THEN
            RAISE EXCEPTION 'event_raw es append-only: solo se permite actualizar '
                'is_valid/invalidated_by/invalidated_reason/invalidated_at';
        END IF;
        RETURN NEW;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_raw_append_only
    BEFORE UPDATE OR DELETE ON event_raw
    FOR EACH ROW EXECUTE FUNCTION event_raw_append_only_guard();

-- Única vía para invalidar un evento: no requiere GRANT UPDATE sobre
-- event_raw (el rol de app no lo tiene, ver V3) porque SECURITY DEFINER
-- corre con los privilegios del dueño de la función (el rol de migración).
CREATE OR REPLACE FUNCTION invalidate_event_raw(p_event_id UUID, p_reason TEXT, p_actor_user_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE event_raw
    SET is_valid = false,
        invalidated_by = p_actor_user_id,
        invalidated_reason = p_reason,
        invalidated_at = now()
    WHERE id = p_event_id
      AND tenant_id = current_setting('app.tenant_id', true)::uuid;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'event_raw % no existe en el tenant actual', p_event_id;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION invalidate_event_raw(UUID, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invalidate_event_raw(UUID, TEXT, UUID) TO ${app_db_user};

-- Reset de datos de demo (usado por DemoSeedService.reset(), perfiles
-- local/demo únicamente). Habilita el bypass del trigger SOLO dentro de su
-- propia transacción (SET LOCAL se revierte solo al hacer COMMIT/ROLLBACK)
-- y solo borra filas del tenant recibido como argumento — nunca un DELETE
-- sin WHERE.
CREATE OR REPLACE FUNCTION demo_reset_events(p_tenant_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_deleted INTEGER;
BEGIN
    PERFORM set_config('app.bypass_append_only', 'on', true); -- true = solo transacción actual
    DELETE FROM event_raw WHERE tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION demo_reset_events(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_reset_events(UUID) TO ${app_db_user};

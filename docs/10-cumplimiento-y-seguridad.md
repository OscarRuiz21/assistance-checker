# Cumplimiento y seguridad

## Marco regulatorio aplicable

### LFPDPPP (Ley Federal de Protección de Datos Personales en Posesión de los Particulares)

Aplica a **toda** persona física o moral que trate datos personales de habitantes de México. Sin excepciones por tamaño.

**Categorías relevantes para el sistema**:

| Tipo de dato | Categoría LFPDPPP | Tratamiento |
|---|---|---|
| Nombre, email, teléfono | Personales | Consentimiento simple (firma del aviso) |
| CURP, RFC, NSS | Personales identificativos | Consentimiento simple |
| Huella dactilar (template) | **Sensibles** (Art. 3) | Consentimiento expreso por escrito |
| Imagen facial | **Sensibles** | Consentimiento expreso por escrito |
| Datos laborales (turnos, horas) | Personales laborales | Justificación contractual |

**Principios obligatorios** (Art. 6):
1. Licitud
2. Consentimiento
3. Información
4. Calidad
5. **Finalidad** ⚠ (no usar datos para nada distinto a lo declarado)
6. Lealtad
7. Proporcionalidad
8. Responsabilidad

### LFT (Ley Federal del Trabajo)

Marco para el cálculo de horas. Ya documentado en [05-motor-calculo-horas.md](./05-motor-calculo-horas.md).

**Art. 804**: el patrón conserva los registros laborales por **5 años**. Esto motiva nuestra retención mínima de eventos.

### NOM-035-STPS-2018

Aplica si el sistema captura datos sobre clima organizacional o factores psicosociales. **No aplica** a este producto (solo asistencia).

### NOM-151-SCFI-2016

Norma sobre conservación de mensajes de datos. Si el sistema sirve como **fuente de verdad legal** para nómina o disputas, debe cumplir con criterios de integridad. Implementación en este sistema:

- Audit log inmutable (append-only, sin updates).
- Eventos crudos con UUID v7 timestamped.
- Snapshot de política aplicada en cada cálculo.
- Retención mínima 5 años LFT.

## Aviso de privacidad (template)

Cada empleado firma al ingresar al tenant. Template:

```
AVISO DE PRIVACIDAD INTEGRAL
[Nombre del Tenant], con domicilio en [...], es responsable del tratamiento
de sus datos personales conforme a la LFPDPPP.

DATOS QUE RECABAMOS:
- Identificativos: nombre, CURP, RFC, NSS, fotografía.
- Laborales: número de empleado, turno, planta, eventos de asistencia.
- Biométricos sensibles (si aplica): plantilla de huella dactilar y/o
  reconocimiento facial, exclusivamente para autenticación de asistencia.

FINALIDAD:
- Registro y cálculo de asistencia laboral.
- Cumplimiento de obligaciones LFT (jornadas, horas extra, cierre nómina).
- Conservación de registros laborales según Art. 804 LFT (5 años).

NO USAREMOS SUS DATOS PARA: marketing, transferencia a terceros sin su
consentimiento, perfilamiento de productividad individual fuera de lo
permitido por LFT, etc.

DERECHOS ARCO:
Acceso, Rectificación, Cancelación, Oposición — solicitud a:
[email-arco@tenant.com] / formulario en [URL].

CONSENTIMIENTO EXPRESO PARA DATOS BIOMÉTRICOS:
[ ] Autorizo el uso de mi huella dactilar/imagen facial únicamente
    para autenticación de asistencia.
[ ] Conozco que puedo revocar este consentimiento, en cuyo caso seré
    inscrito en métodos alternativos (NFC + PIN).

Firma: ___________________________  Fecha: ____________
```

Embebido en admin web durante onboarding del empleado. Sin firma del consentimiento expreso, no se permite enrolamiento biométrico.

## Procedimiento ARCO (Acceso, Rectificación, Cancelación, Oposición)

LFPDPPP da derecho al titular de datos personales a ejercer ARCO en cualquier momento.

### Implementación MVP

```
1. Empleado clickea "Solicitar mis datos" en admin web (si tiene cuenta) o
   envía email a arco@tenant.com.
2. Sistema (o equipo de soporte) ejecuta SQL pre-armado:
   
   -- Acceso (export de todo lo del empleado)
   SELECT * FROM employee WHERE tenant_id = ? AND id = ?;
   SELECT * FROM event_raw WHERE tenant_id = ? AND employee_id = ?;
   SELECT * FROM attendance_day WHERE tenant_id = ? AND employee_id = ?;
   ...
   
3. Genera reporte PDF/JSON.
4. Entrega al titular en plazo legal (20 días hábiles según LFPDPPP).

Para Cancelación:
   UPDATE employee SET status='terminated', deleted_at=now() WHERE id=?;
   DELETE FROM biometric_template WHERE employee_id=?;  -- biométrica SIEMPRE
   -- Eventos quedan por obligación LFT 5 años, anonimizados:
   UPDATE event_raw SET raw_payload = jsonb_strip_nulls(raw_payload - 'identifying_info')
       WHERE employee_id = ?;
```

### v1.1: ARCO automatizado

Endpoint dedicado:
```
POST   /api/v1/arco-requests
       (empleado o representante, con verificación de identidad)
GET    /api/v1/arco-requests/{id}
POST   /api/v1/arco-requests/{id}/process    (admin/RH)
```

## Encriptación

### At-rest

```
PostgreSQL RDS:
  ✓ TDE habilitado (transparent disk encryption)
  ✓ KMS key gestionada por AWS o customer-managed (CMK)
  ✓ Snapshots y backups encriptados automáticamente

S3 (exports CSV, archivos):
  ✓ SSE-S3 default, o SSE-KMS para datos sensibles

Plantillas biométricas (futuro v1.1):
  ✓ Encriptación a nivel de aplicación con KMS
  ✓ DEK (Data Encryption Key) por tenant, envuelta con KEK (KMS)
  ✓ Plantilla siempre cifrada en biometric_template.template_blob
  ✓ Decryption solo en momento de matching (server-side o cache local en kiosko)
```

### In-transit

```
✓ TLS 1.2+ obligatorio en TODO el tráfico
✓ HSTS header con max-age >= 1 año
✓ Cipher suites modernos (no RC4, no 3DES)
✓ Certificados ACM gestionados por AWS
✓ Cliente NFC card → kiosko: out-of-scope (RFID no encriptado, asumido OK)
```

### Recomendación pgcrypto (v1.1)

Añadir cifrado a nivel de columna para CURP, RFC, NSS sobre TDE:

```sql
-- Encriptación
UPDATE employee SET curp_encrypted = pgp_sym_encrypt(curp, 'key-from-kms');

-- Decryption en read
SELECT pgp_sym_decrypt(curp_encrypted, 'key-from-kms') AS curp FROM employee;
```

Esto protege contra "alguien tiene credenciales DB pero no la KMS key". Defensa adicional sobre TDE.

### Datos biométricos sensibles — política especial

LFPDPPP los clasifica como sensibles. Reglas obligatorias:

1. **Encriptación SIEMPRE**, no configurable.
2. **Consentimiento expreso por escrito** del titular.
3. **Borrado al ejercer derecho de cancelación** dentro de plazo legal.
4. **Acceso restringido** a roles que lo justifiquen (super_admin, tenant_admin).
5. **Audit log** detallado de quién accede a la plantilla.

⚠ **Decisión actual del cliente**: "conserva indefinido tras baja, solo desactiva acceso". Ver bandera amarilla en [04-flujos-edge-cases.md](./04-flujos-edge-cases.md). **Recomendación**: evolucionar a "borrado tras 90 días" en v1.1.

## Autenticación

### AWS Cognito (usuarios)

Configuración recomendada:

```
User Pool:
  ├─ MFA opcional en MVP (obligatorio en v1.1 para tenant_admin)
  ├─ Password policy:
  │   - Mínimo 10 caracteres
  │   - Al menos 1 mayúscula, 1 minúscula, 1 número, 1 símbolo
  │   - No password reuse últimos 5
  │   - Expiración 90 días para tenant_admin/rh
  ├─ Account lockout: 10 intentos fallidos → 15 min lock
  ├─ Recovery: email verificado obligatorio
  └─ JWT lifecycle: access 1h, refresh 30 días
  
Triggers Lambda:
  ├─ Pre-sign-up: validar email corporativo
  ├─ Post-confirmation: crear app_user en DB
  └─ Pre-token: agregar claims tenant_id, role, plant_ids
```

### Device JWT (web kiosko)

```
Flow de pairing → device_token sin expiración pero revocable.

Algoritmo: RS256 con KMS-managed private key.
Claims:
  {
    "kid": "device-key-2026",
    "sub": "kiosko_uuid",
    "tenant_id": "...",
    "plant_id": "...",
    "type": "device",
    "iat": 1714238400
    // sin "exp"
  }

Verificación en cada request:
  1. JWT válido (firma + claims).
  2. kiosko.status = 'active' (cache 60s).
  3. SET app.tenant_id = claims.tenant_id en DB session.

Revocación:
  - admin marca kiosko.status = 'retired'.
  - Cache se invalida en 60s.
  - Próximo request → 410 Gone, kiosko muestra "fuera de servicio".
```

### Hardware ZKTeco

Sin Bearer auth (limitación del protocolo ADMS). Validación por:
- SN del aparato registrado en DB.
- IP whitelist (opcional, si cliente acepta).
- TLS 1.2+ obligatorio.

## Authorization (RBAC)

```
Role: super_admin
  Acceso: global (todos los tenants).
  Endpoints: TODOS, incluido /policy-profiles globales.
  Caso de uso: equipo del SaaS.

Role: tenant_admin
  Acceso: su tenant.
  Endpoints: TODOS los del tenant.
  Caso de uso: dueño de la empresa cliente.

Role: rh
  Acceso: su tenant.
  Endpoints: empleados, plantas, pay periods, exports, audit.
  Sin acceso a: policy-profiles globales, tenant config.
  Caso de uso: equipo de RH del cliente.

Role: supervisor
  Acceso: solo plantas asignadas (plant_ids del JWT).
  Endpoints: GET/POST sobre dashboards, eventos, correcciones,
             review queue, rescue mode — TODOS filtrados por plant.
  Sin acceso a: empleados create/delete, pay-periods/reopen,
                policy-profiles, otros plants.
  Caso de uso: jefe de turno o de planta.
```

Implementación:

```java
@RestController
public class CorrectionController {
    
    @PostMapping("/corrections")
    @RequiresRole({"supervisor","rh","tenant_admin"})
    @RequiresPlantAccess(planParam = "plantId")  // valida plant_ids del JWT
    public ResponseEntity<CorrectionDTO> create(...) { ... }
}
```

## Audit log

### Cobertura obligatoria

Toda mutación en estas tablas dispara trigger de Postgres:

```
employee, plant, kiosko, nfc_card, shift, holiday,
policy_profile, plant_policy_history,
pay_period (close/lock/reopen),
event_correction (siempre, no-genera audit del original),
biometric_template (alta y baja),
app_user (alta, baja, cambio de role)
```

### Estructura

```
audit_log {
  id, tenant_id,
  actor_user_id (NULL si sistema),
  actor_kiosko_id (cuando captura),
  action ('TG_OP'),
  entity_type, entity_id,
  before JSONB, after JSONB,
  reason, ip_address, user_agent,
  occurred_at
}
```

### Retención

```
Default: 7 años (mayor que retención de datos por defensa legal).
Configurable por tenant en tenant.retention_config.audit_years.
Después: archivado a S3 Glacier (no DELETE).
```

## Backups y disaster recovery

### MVP (Multi-AZ, no multi-region)

```
RDS Multi-AZ:
  ✓ Failover automático intra-región (~60s downtime)
  ✓ Backups automáticos diarios, retención 30 días
  ✓ PITR (Point-in-Time Recovery) hasta 5 minutos
  
S3:
  ✓ Versioning ON
  ✓ Lifecycle policy: transition a Glacier tras 90 días
  ✓ Cross-region replication: NO en MVP
```

### Snapshots manuales

```
Antes de cada cierre de pay_period:
  → snapshot manual con tag period_id
  → documentado en runbook
  
Antes de cada deploy a producción:
  → snapshot manual automático en pipeline
  → retención 7 días post-deploy
```

### v2: Multi-region disaster recovery

```
Read replica en us-west-2 (o mx-central-1 cuando RDS lo soporte).
Failover plan documentado.
RTO objetivo: 4 horas. RPO objetivo: 5 minutos.
```

## Designación de responsable LFPDPPP

Antes del go-live el tenant debe:

1. **Designar oficial de protección de datos** (puede ser el dueño/RH).
2. **Notificar al INAI** la creación del registro de datos personales (formato gratis en inai.org.mx).
3. **Preparar el aviso de privacidad** firmable por empleados (ver template arriba).
4. **Establecer email ARCO** (típicamente: arco@empresa.com).
5. **Procedimiento documentado** para responder a solicitudes en plazo legal.

El sistema **embebe** la mayoría de estos requisitos. Lo que **no** embebe es la designación legal — ese paso lo hace el cliente con apoyo de su asesor legal.

## Inventario de PII en el sistema

Documento útil para auditoría:

| Dato | Tabla / Campo | Sensibilidad | Encriptación |
|---|---|---|---|
| Nombre | employee.full_name | Personal | TDE |
| CURP | employee.curp | Personal identificativo | TDE (+pgcrypto v1.1) |
| RFC | employee.rfc | Personal identificativo | TDE (+pgcrypto v1.1) |
| NSS | employee.nss | Personal identificativo | TDE (+pgcrypto v1.1) |
| Email | app_user.email | Personal | TDE |
| Hash PIN | employee.pin_hash | Auth | bcrypt + TDE |
| Plantilla biométrica | biometric_template.template_blob | **Sensible** | KMS app-level + TDE |
| IP address | audit_log.ip_address | Personal (con datos asociados) | TDE |
| Eventos asistencia | event_raw | Personal laboral | TDE |

## Checklist de seguridad para go-live

```
☐ Aviso de privacidad LFPDPPP redactado, revisado por legal del cliente
☐ Aviso embebido en admin web y kiosko (al primer login)
☐ Procedimiento ARCO operativo (email + SQL pre-armado)
☐ Cognito MFA habilitado para tenant_admin (opcional MVP, oblig. v1.1)
☐ Password policy aplicada
☐ Account lockout configurado
☐ TLS 1.2+ enforced en ALB y CloudFront
☐ HSTS header
☐ TDE habilitado en RDS
☐ KMS keys con rotación anual
☐ IAM roles least-privilege (no usar root, no usar admin policies)
☐ Secrets en Secrets Manager, no en env vars
☐ VPC con subnets privadas para ECS y RDS
☐ Security groups restrictivos
☐ WAF habilitado en ALB con reglas básicas (AWS managed rules)
☐ CloudTrail habilitado
☐ Backups RDS automated 30d + manual snapshots pre-cierre
☐ Retention configurable por tenant con defaults conservadores
☐ Audit log via triggers en todas las tablas mutables
☐ Penetration test interno: cross-tenant attempts en 20+ endpoints
☐ Rate limits aplicados
☐ Body logging solo en errores ≥ 400 con redacción PII
☐ Lista de PII inventariada (este documento)
☐ Designación de responsable LFPDPPP por parte del cliente
```

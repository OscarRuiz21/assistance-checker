# ADR-003: ZKTeco ADMS Push Protocol (no Standalone SDK)

**Estado**: Aceptado
**Fecha**: 2026-04-27
**Decisores**: Tech Lead

## Contexto

Necesitamos integrar dispositivos de captura biométrica/RFID (ZKTeco K50 Pro) con el backend en AWS. ZKTeco provee dos esquemas de integración:

1. **Standalone SDK** (zkemkeeper.dll, "Pull SDK"):
   - Windows COM/ActiveX 32-bit.
   - C# / VB.NET.
   - Modelo: backend abre TCP al IP del dispositivo y se suscribe a eventos.
   - Documentación: 2013, no actualizada.

2. **ADMS Push Protocol**:
   - HTTP/HTTPS estándar.
   - Modelo: dispositivo inicia POST hacia un servidor cloud.
   - Nativo en firmwares modernos del K50 Pro.
   - Compatible con cualquier lenguaje en cualquier OS.

El usuario nos compartió `STANDALONE_SDK.rar` como punto de partida. Su análisis técnico mostró:

- DLLs de 2016, 32-bit Windows.
- Demos solo en .NET Framework Windows.
- Diseñado para LANs corporativas on-premise (PC Windows en planta conectado al kiosko vía LAN).
- **Incompatible con backend Java en Linux/AWS**.

## Decisión

**Usamos ADMS Push Protocol. NO usamos el Standalone SDK como parte del backend.**

El Standalone SDK queda **archivado en `docs/vendor/`** como referencia para escenario de contingencia (Plan B Windows Bridge — ver [ADR-007](./ADR-007-windows-bridge-plan-b.md), pendiente).

## Razonamiento

### Por qué Standalone SDK NO sirve

1. **Plataforma incompatible**: Windows-only, no corre en Linux/AWS.
2. **Modelo invertido**: requiere que el backend alcance el IP del dispositivo en cada planta. Para SaaS multi-tenant esto significa VPN site-to-site con cada cliente — operacionalmente inviable.
3. **SDK obsoleto**: documentación 2013, código demo 2009. ZKTeco ha movido su narrativa a ZKBio Cloud + Push protocol.
4. **Sin push capability**: 100% pull-based, diseñado para LANs corporativas.

### Por qué ADMS Push SÍ funciona

1. **Cloud-native**: el dispositivo hace HTTP POST a tu API. Cero infra adicional.
2. **NAT-friendly**: planta detrás de NAT corporativo no es problema, solo necesita salida HTTPS.
3. **Lenguaje-agnóstico**: backend Java o lo que sea, recibe POSTs como cualquier otro endpoint.
4. **Soportado por firmware moderno** (2022+) del K50 Pro y otros modelos ZKTeco.
5. **Bidireccional**: device pide comandos pendientes via `/iclock/getrequest`, backend los entrega para sync de empleados, bloqueo de tarjetas, etc.

### Implementación

Backend Spring Boot expone:
```
POST   /iclock/cdata?SN=...&table=ATTLOG     ← eventos
POST   /iclock/cdata?SN=...&table=OPERLOG    ← operaciones
GET    /iclock/cdata?SN=...&options=all       ← config inicial
GET    /iclock/getrequest?SN=...&INFO=...     ← comandos pendientes
POST   /iclock/devicecmd?SN=...               ← ack de comandos
POST   /iclock/edata?SN=...&type=fp           ← templates biométricos (v1.1)
```

Validación de identidad por **Serial Number** del aparato registrado en `kiosko_zkteco.device_serial` (subtabla específica del tipo K50, ver [03-modelo-datos.md §4.2](../03-modelo-datos.md)). Sin SN registrado → 404. Detalle en [09-hardware-zkteco.md](../09-hardware-zkteco.md).

## Consecuencias

### Positivas

- Backend Java en AWS sin Windows en el medio.
- Cliente cumple con NAT corporativo sin VPN.
- Reduce alcance del MVP en ~3 person-months (no Android nativo, no Windows agent).
- Spec ADMS estándar, alternativa a otros vendors (Hikvision ISAPI, etc.) si fuera necesario migrar.

### Negativas

- Dependencia del firmware del aparato. Si el K50 Pro recibido no tiene ADMS habilitado: Plan B (cambio de modelo o Windows Bridge).
- Validación por SN es débil ante un atacante motivado (puede falsificarse). Mitigaciones: WAF, rate limit, IP whitelist opcional, TLS mutual auth en v1.1.
- Documentación oficial ADMS de ZKTeco es escasa; reverse-engineering parcial necesario.

## Plan B documentado

Si spike W3-W4 muestra que ADMS no funciona en el K50 Pro recibido:

1. Cambiar a modelo más nuevo confirmado con ADMS (MB360 o V5L Pro).
2. Si tampoco resuelve, activar [ADR-007](./ADR-007-windows-bridge-plan-b.md): mini-PC Windows en planta usando el Standalone SDK archivado para puentear.

El Standalone SDK queda como **artefacto de contingencia**, no producto.

## Validación de la decisión

Spike de validación en W3-W4:

```
Criterios de éxito (todos requeridos):
  ✓ Llega POST con SN, timestamp, user_id, type
  ✓ HTTPS funciona
  ✓ Reconexión recupera eventos del buffer interno
  ✓ Formato del payload reproducible
  ✓ Heartbeat llega periódicamente
```

Si falla cualquiera, activar Plan B.

## Re-evaluar cuando

- Aparezca cliente con K50 Pro firmware viejo que no soporte ADMS.
- ZKTeco lance API moderna alternativa (REST oficial) — revisar si justifica migración.
- Otros vendors (Hikvision ISAPI, Anviz, Suprema) sean parte del producto — evaluar si Strategy pattern de captura cubre múltiples protocolos.

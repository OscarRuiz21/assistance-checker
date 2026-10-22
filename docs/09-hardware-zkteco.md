# Hardware ZKTeco

## Modelo seleccionado: ZKTeco K50 Pro

| Especificación | Valor |
|---|---|
| Captura | Huella + RFID + PIN |
| Capacidad | 3,000 usuarios, 5,000 huellas, 100,000 logs locales |
| Comunicación | Ethernet + Wi-Fi |
| Protocolo Push | ADMS (Anti-passback Data Management System) |
| Pantalla | 2.4" color TFT |
| Conectividad LAN | TCP/IP, USB |
| Voltaje | 12V DC |
| Distribuidores MX | Tepic Tecnologías, ID Mexicana, ZKTeco MX directo |
| Lead time | 1–3 semanas con stock; 3-6 si lo importan |

## ¿Por qué K50 Pro y no otro?

Comparativa con alternativas evaluadas:

| Modelo | Captura | Push | Veredicto |
|---|---|---|---|
| **ZKTeco K50 Pro** | Huella + RFID + PIN | ADMS | **Elegido** — cumple MVP, barato, mercado abundante |
| ZKTeco K40 | Huella + RFID | ADMS | Descartado: capacidad menor, sin pantalla TFT |
| ZKTeco MB360 | Huella + Cara + RFID | ADMS | Reservado para clientes que pidan face en v1.1 |
| ZKTeco SpeedFace V5L | Cara + Huella + RFID | ADMS+SDK | Reservado para clientes premium |
| Hikvision DS-K1T341 | Cara + Huella | ISAPI Push | Alternativa si ZKTeco falla, mejor SDK pero caro |
| NGTeco AS10 | Huella | ❌ Sin push real | **Descartado**: solo export Excel, no SaaS-friendly |

## Protocolo ADMS Push

**Concepto clave**: el dispositivo (no el backend) inicia la conexión HTTP y empuja eventos. Eso permite:

- Cero VPN entre tu cloud y planta del cliente.
- Cero exposición de IP del aparato a internet.
- Backend Java en AWS recibe POSTs como cualquier API REST.
- Compatible con NAT/firewalls corporativos.

```
Planta del cliente                          AWS (tu backend)
──────────────────                          ─────────────────
K50 Pro
   │
   │ HTTPS POST cada que hay evento
   │ + heartbeat periódico
   │
   ▼
api.tudominio.com/iclock/cdata
                  │
                  ▼
        Tu backend Spring Boot
        Push Adapter parser
                  │
                  ▼
        Evento normalizado a /events
                  │
                  ▼
        event_raw inserción
```

### Endpoints del protocolo (lado backend, Push Adapter)

```
GET    /iclock/cdata?SN=...&options=all
       Device pide configuración inicial
       Response: pares clave-valor con servidor URL, intervalo de heartbeat, etc.

POST   /iclock/cdata?SN=...&table=ATTLOG&Stamp=...
       Device envía batch de eventos de asistencia
       Body: registros tabulares texto plano (formato propietario)
       Response: "OK" + Stamp incrementado

POST   /iclock/cdata?SN=...&table=OPERLOG&Stamp=...
       Device envía operaciones (enroll, delete user, etc.)
       Mismo patrón que ATTLOG

GET    /iclock/getrequest?SN=...&INFO=...
       Device pregunta si hay comandos pendientes para él
       Response: lista de comandos a ejecutar (o "OK" si nada)

POST   /iclock/devicecmd?SN=...
       Device confirma ejecución de un comando
       Body: ID de comando + status

POST   /iclock/edata?SN=...&type=fp
       Device sube template biométrica (v1.1)
```

### Validación de identidad del device

ADMS no usa Bearer JWT. La identidad la dan dos cosas:

1. **Serial Number (SN)** del aparato como query param.
2. Registro previo del SN en la subtabla `kiosko_zkteco.device_serial` (ver [03-modelo-datos.md §4.2](./03-modelo-datos.md)).

Si llega petición con SN no registrado → respuesta 404 (el K50 lo interpreta como "este servidor no me conoce" y reintenta).

**Limitación**: el SN puede ser falsificado por un atacante que sepa cuál usar. Mitigación adicional:
- Lista blanca de IPs si el cliente acepta restringir su red saliente.
- TLS mutual auth con cert client en aparato (ZKTeco lo soporta en algunos firmwares).
- Para MVP: solo SN + WAF basico. Aceptable porque el evento es solo "X marcó", no operación crítica.

### Comando de control (server → device)

Para enviar empleado nuevo o bloquear tarjeta al K50 Pro:

```
1. Backend inserta en tabla device_command:
   { kiosko_id, command_type: 'add_user', payload: {...}, status: 'pending' }

2. Próxima vez que K50 hace GET /iclock/getrequest, backend responde:
   C:1:DATA UPDATE USERINFO PIN=12345 Name=Juan Pri=0 Passwd=0...
   (formato texto propietario)

3. K50 ejecuta el comando localmente.

4. K50 hace POST /iclock/devicecmd con confirmación.

5. Backend marca command status = 'acked'.
```

Esta vía permite:
- Sync de empleados nuevos.
- Bloqueo de tarjetas perdidas.
- Update de PIN.
- Borrado de usuarios dados de baja.
- Reload de configuración.

## Distribuidores recomendados

1. **Tepic Tecnologías** (tepictec.com) — distribuidor oficial ZKTeco MX, soporte en español.
2. **ID Mexicana** (idmexicana.com) — alternativa con buena documentación.
3. **ZKTeco México directo** — útil si necesitas demo unit prestado.
4. **Amazon MX** — solo si necesitas urgente y no es distribuidor oficial.

### Preguntas obligatorias al distribuidor antes de pagar

1. *"¿El K50 Pro tiene firmware con soporte ADMS / Cloud Server habilitado?"*
2. *"¿Pueden enviarme el manual del menú Communication → Cloud Server?"*
3. *"¿Qué versión de firmware tiene la unidad? Necesito 2022+ idealmente."*
4. *"¿Garantía y RMA: cuánto tiempo y proceso si falla?"*

Si el distribuidor titubea o no sabe responder, cambia de distribuidor.

## Spike de validación ADMS (W3-W4)

Reservar 1 día completo del Tech Lead.

### Pre-requisitos (preparar W2)

```
☐ Subdominio dev.tudominio.com con SSL válido
☐ Endpoint stub /iclock/cdata que log-ea a archivo
☐ Túnel ngrok como fallback si DNS aún no listo
☐ K50 Pro recibido, encendido, en red (cable o Wi-Fi)
```

### Pasos del spike (4-6h)

```
1. Configurar K50 Pro:
   Menu → Comm → Cloud Server Setting
     Server Address: dev.tudominio.com
     Server Port: 443
     Enable Domain Name: ON
     HTTPS: ON
     Proxy: OFF
   Menu → Comm → Network: configurar IP/Wi-Fi

2. Test de conectividad:
   Menu → Test → Cloud Server (si existe)
   O ping desde el aparato al servidor

3. Crear usuario de prueba:
   Menu → Users → New User
   Asignar huella

4. Marcar 1 evento (poner huella)

5. Verificar log en backend:
   ¿Llegó POST a /iclock/cdata?
   ¿Con qué payload? Capturar formato exacto

6. Generar 5-10 eventos más, capturar todos los payloads

7. Test de offline:
   Apagar internet del K50 5 min
   Marcar 2 eventos
   Reconectar
   Verificar que llegan los eventos retrasados
```

### Criterios de éxito (todos requeridos)

```
✓ Llega POST con SN, timestamp, user_id, type
✓ HTTPS funciona (no solo HTTP plano)
✓ Reconexión recupera eventos del buffer interno
✓ Formato del payload reproducible
✓ Heartbeat llega periódicamente
```

### Criterios de fallo → activar Plan B

Si CUALQUIER criterio falla:

1. **Plan B-1**: cambiar a modelo más nuevo (MB360 o V5L Pro).
2. **Plan B-2**: bridge Windows en planta (ver [ADR-007](./adrs/ADR-007-windows-bridge-plan-b.md), pendiente).

### Documentación de salida del spike

El TL escribe documento `docs/spikes/ADMS-K50Pro.md` con:
- Versión de firmware probada.
- Formato exacto del payload (samples capturados).
- Configuraciones del aparato (screenshots o pasos).
- Comandos device → backend que detectó.
- Comandos backend → device que detectó.
- Performance: latencia ping, latencia evento → POST.
- Recomendación final: ¿proceder con plan o activar Plan B?

## Instalación física en planta

Runbook detallado: ver [11-riesgos-y-runbooks.md](./11-riesgos-y-runbooks.md). Resumen:

```
PRE-VISITA
  ☐ Confirmación de red disponible (cable o Wi-Fi suficiente)
  ☐ Punto eléctrico cerca (12V DC adapter incluido)
  ☐ Ubicación accesible para obreros, visible
  ☐ Soporte físico (anclaje a pared o pedestal)

EN PLANTA
  ☐ Montaje físico (taladro, taquetes, anclas)
  ☐ Conectar red + electricidad
  ☐ Setup inicial: idioma, hora, IP, server URL
  ☐ Pairing en backend (admin web → kiosko nuevo → confirmar SN)
  ☐ Sync inicial: pull de empleados de la planta
  ☐ Test: 3-5 obreros voluntarios marcan checada de prueba
  ☐ Verificar en admin web que aparecen los eventos
  ☐ Capacitación al supervisor: cómo desbloquear si se traba,
    cómo escalar si pasa algo raro
```

## Plan B Windows Bridge (no MVP, documentado)

Si el spike falla y ningún modelo ZKTeco soporta ADMS confiablemente, plan de contingencia:

```
ARQUITECTURA DE EMERGENCIA:

Planta                              AWS
──────                              ───
ZKTeco K50 Pro                      Backend Java
   │                                    ▲
   │ TCP:4370 (LAN local)               │ HTTPS POST
   ▼                                    │
Mini-PC Windows                         │
+ .NET 6 service                        │
+ zkemkeeper.dll (Standalone SDK)       │
+ buffer SQLite local                   │
└─────────────────────────────────────────┘
   Internet de la planta
```

**Componentes a construir** (~2 semanas extra):

1. Mini-PC Windows 10 IoT
2. Servicio .NET 6 con auto-restart
3. COM interop a `zkemkeeper.dll` (provisto en `docs/vendor/`)
4. Suscribe a eventos `OnAttTransactionEx`, `OnFinger`, `OnVerify`
5. Buffer local SQLite ante red caída
6. Reintento exponencial al backend
7. Logs estructurados a Windows Event Log

**Trade-offs**:
- (-) Hardware extra por planta.
- (-) Complejidad operacional: mantener PCs Windows en plantas.
- (-) +1 componente custom mantenido por equipo (.NET no nativo del equipo).
- (-) +2-3 semanas al MVP.
- (-) Ventana de pérdida si cae el mini-PC (vs cae el K50 directo).

**Trigger para activar**: spike W4 muestra ADMS no funciona Y modelos alternativos no resuelven.

Detalle completo en [ADR-007](./adrs/ADR-007-windows-bridge-plan-b.md) (pendiente de redactar).

## SDK Standalone — manejo del archivo recibido

El user compartió `STANDALONE_SDK.rar` (12.6 MB) durante el diseño. Análisis:

- **NO sirve** para arquitectura cloud-first (ver [ADR-003](./adrs/ADR-003-zkteco-adms-no-sdk.md)).
- Windows 32-bit COM/ActiveX, modelo pull (backend → device).
- Útil **solo** como Plan B (Windows Bridge).

**Manejo**: archivar en `docs/vendor/zkteco-standalone-sdk-6.2.5.42/` con README explicativo:

```markdown
# ZKTeco Standalone SDK 6.2.5.42

**Estado**: archivado como Plan B (ADR-007).
**NO usar en código de producción.**

Este SDK es Windows COM/ActiveX 32-bit, incompatible con el backend
Java en Linux/AWS. Se conserva como referencia para escenario de
contingencia (bridge Windows en planta).

Última actualización del SDK: 2017. Sin soporte oficial.
```

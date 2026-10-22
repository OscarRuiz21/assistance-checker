# Demo MVP — Plan de construcción

Demo de 7-10 min para mostrar el flujo de captura y visualización. **No es producción**: es un subset funcional construido sobre el mismo stack que la solución final, optimizado para storytelling presencial.

## Objetivo

Mostrar:
1. Captura de entrada con tarjeta NFC desde un iPhone normal del bolsillo del prospecto.
2. Captura de PIN como fallback.
3. Visualización en tiempo real en pantalla web admin.
4. Descarga CSV listo para nómina.
5. Edge cases creíbles (tarjeta bloqueada, PIN equivocado, etc.).

## Restricción crítica — NFC en iPhone

iOS **no soporta Web NFC API** (solo Chrome Android). Para que un iPhone "lea" un tag NFC y dispare flujo web la única vía viable es:

**Tag NFC programado con un NDEF URL** (`https://demo.app/k?u=04A3B2C1D5E890`). Cuando el usuario acerca el iPhone:

1. iOS detecta el tag y muestra notificación nativa "Abrir demo.app"
2. Usuario toca → Safari abre la URL con el UID embebido
3. La PWA extrae `u`, busca empleado, pide PIN, registra evento

Es la misma UX de un menú QR en restaurante pero con NFC. Funciona en cualquier iPhone con iOS 14+ sin instalar app.

**Implicación para producción**: el web kiosko mode real (que sí va en kioskos dedicados tipo tablet Android o PC en planta) usa Web NFC API directa. El demo en iPhone usa este truco NDEF URL. Vale la pena explicarlo así al cliente: "en el kiosko real, el flujo es aún más rápido — aquí lo simulamos con su iPhone".

## Decisiones de stack (confirmadas)

| Dimensión | Elección | Implicación |
|---|---|---|
| Backend | Spring Boot 3 + Postgres 16 + Flyway | Mismo que producción. Reusas modelo de datos, RLS opcional desactivada para demo. |
| Hosting demo | AWS Lightsail 4GB IPv4 monolítico (Postgres + Spring Boot + Caddy en una VM) | ~$33 USD/mes (~$600 MXN). Validado contra AWS Pricing API (mayo 2026). Reduce 50% vs Fargate+RDS+ALB sin sacrificar funcionalidad del demo. |
| Hosting producción | ECS Fargate Graviton + RDS Multi-AZ + ALB | Graviton ARM ahorra 20% en compute. Multi-AZ obligatorio desde 1er cliente real. |
| NFC físico | Stickers NTAG213/215 genéricos | $5 MXN c/u. Programar con app gratuita "NFC Tools" desde iPhone. Pegar en cartón rígido o acrílico con logo. |
| Frontend (kiosko + admin) | React 18 + TypeScript + Vite + Tailwind + shadcn/ui + TanStack Query/Router + Recharts | Mismo repo, code-splitting por ruta. Kiosko bundle ~50KB, admin ~200KB. Build sirve estáticos directo desde Spring Boot. |
| Real-time | Server-Sent Events (SSE) | Más simple que WebSocket para 1-vía server→admin. |
| Formato demo | Presencial en oficina del cliente | Optimizamos para impacto visual y cierre. |

## Modelo de datos del demo (subset)

Tablas que conservamos (ver [03-modelo-datos.md](./03-modelo-datos.md) para el modelo completo de producción):

```
tenant         (1 fila fija: "Empresa Demo SA de CV")
plant          (1 fila fija: "Planta Querétaro")
employee       (~30 con CURP/RFC realistas)
nfc_card       (10-15 mapeadas a empleados, 1 bloqueada para edge case)
kiosko         (base, 1 fila tipo web_kiosko)
kiosko_web     (subtipo, paired al inicio del demo)
event_raw      (versión simplificada: sin event_correction ni review_queue)
```

Tablas **excluidas** del demo (existen en prod, no en demo):
- `shift`, `holiday`, `policy_profile`, `plant_policy_history` (no hay motor de cálculo)
- `attendance_day`, `pay_period`, `pay_period_summary` (no hay ciclo de nómina)
- `audit_log`, `biometric_template` (no relevante para demo)
- `device_command`, `kiosko_zkteco`, `kiosko_heartbeat` (no hay K50 hardware)

PIN para el demo: hardcoded `1234` para todos los empleados (ahorra tiempo en vivo).

## Endpoints del backend

```
# Operación demo
POST   /api/demo/seed                  Genera 200 eventos de los últimos 3 días
POST   /api/demo/reset                 Borra eventos y vuelve a estado inicial

# Captura
GET    /api/employees/by-card/{uid}    Lookup empleado por NFC UID (404 si no existe)
POST   /api/events                     Registra entrada {employee_id, capture_method, pin?}

# Visualización admin
GET    /api/events                     Paginado + filtros (date, employee_id, plant_id)
GET    /api/events/stream              SSE para tiempo real
GET    /api/events/export.csv          Descarga CSV con headers en español
GET    /api/dashboard/kpis             {today_entries, unique_employees, late_count, last_24h_chart}
GET    /api/employees                  Lista con filtros
```

Sin Cognito en el demo: bypass auth con un header `X-Demo-Token: demo123` para los endpoints admin. El kiosko sin auth (cualquiera con la URL puede registrar — es un demo).

## Estructura del repo

```
assistance-checker/
├── backend/                    Spring Boot 3 + Postgres + Flyway
│   ├── src/main/java/...
│   └── src/main/resources/
│       └── static/             ← frontend buildea aquí (Vite outDir)
├── frontend/                   Vite + React + TypeScript
│   ├── src/
│   │   ├── routes/
│   │   │   ├── k/              Kiosko: /k, /k/manual, /k/error
│   │   │   └── admin/          Admin: /admin, /admin/empleados, /admin/tarjetas
│   │   ├── components/ui/      shadcn/ui copy-paste (Button, Dialog, Table, Form, Toast, Card)
│   │   ├── lib/api.ts          Cliente HTTP tipado
│   │   ├── lib/sse.ts          Hook useEventStream para tiempo real
│   │   └── lib/audio.ts        Beep + vibración del kiosko
│   ├── tailwind.config.js
│   ├── vite.config.ts          build.outDir = '../backend/src/main/resources/static'
│   └── package.json
├── infra/                      Terraform AWS (VPC, RDS, Fargate, ALB, ACM, Route53)
└── docs/                       este folder
```

CI: `cd frontend && npm run build && cd ../backend && ./gradlew bootJar` → un solo jar deployable.

## Web Kiosko (PWA)

```
/k                Pantalla espera con reloj grande, mensaje "Acerque su tarjeta"
/k?u={uid}        Llegada por NFC:
                  1. Loader 200ms (que se sienta real)
                  2. Lookup → si OK: muestra foto + nombre + "Ingresa tu PIN"
                  3. Teclado numérico touch-friendly (pin 4 dígitos)
                  4. POST /api/events
                  5. Animación check verde + nombre + hora "✓ Entrada 14:32:15"
                  6. Auto-redirect a /k después de 3s
/k/manual         Captura sin NFC: número empleado + PIN
/k/error          Tarjeta no registrada / bloqueada (rojo, claro, no asusta)
```

**Detalles que venden:**
- Splash screen con logo del cliente al arrancar
- Sonido suave de "beep" al registrar (Web Audio API)
- Vibración corta del iPhone al registrar (Vibration API funciona en Safari)
- Modo full-screen (manifest.json `display: standalone`)
- Reloj live grande en pantalla espera
- Foto de empleado (placeholder con iniciales sobre fondo de color generado del nombre)

## Web Admin

```
/admin                Dashboard:
                      - 4 KPI cards: entradas hoy, empleados únicos, retardos, ausentes
                      - Tabla en vivo (SSE) últimos 20 eventos, último resaltado verde 2s
                      - Mini-chart línea: entradas/hora últimas 24h
                      - Filtros: fecha, planta, empleado, método
                      - Botones: [Sembrar datos demo] [Descargar CSV] [Reset]
/admin/empleados      Lista simple con búsqueda
/admin/tarjetas       Mapeo tarjeta-empleado (tabla editable)
/admin/login          Mock login (cualquier credencial entra)
```

Diseño: Tailwind, paleta corporate sobria (azul + gris + verde para success), tipografía Inter, mobile-aware (admin se puede ver en iPad si el cliente quiere).

## Guion del demo (7-10 min)

| Min | Acción | Lo que dices |
|---|---|---|
| 0:00-0:30 | Abres laptop con `/admin` vacío | "Imaginemos su Planta Querétaro a las 6 AM" |
| 0:30-1:30 | Click [Sembrar datos demo] → KPIs cobran vida | "Estos son 3 días de operación normal: 487 empleados, 3% retardos" |
| 1:30-2:00 | Sacas el iPhone y los stickers | "Ahora veamos a un empleado llegando" |
| 2:00-3:30 | Acercas sticker → iOS notif → Safari abre → ves nombre → PIN 1234 → ✓ | "María González, su check-in queda registrado" |
| 3:30-4:00 | Apuntas a laptop → evento aparece highlighted en verde | "Y aquí ya lo ve RH en tiempo real" |
| 4:00-5:30 | Edge cases: tarjeta bloqueada, PIN equivocado | "Si pierde su tarjeta, queda inservible al instante" |
| 5:30-6:30 | Click [Descargar CSV] → abres en Excel | "Esto entra directo a su sistema de nómina, sin reescribir nada" |
| 6:30-7:30 | Captura manual (PIN sin NFC) en iPhone | "Y si olvidó su tarjeta, tampoco bloquea producción" |

**Plan B si AWS falla en vivo**: tener una versión local corriendo con ngrok como respaldo. Demo offline-friendly: el SSE auto-reconecta.

## Setup AWS para el demo

```
Stack monolítico Lightsail (validado contra AWS Pricing API):
- 1× Lightsail Linux Instance 4GB IPv4 ($23.54 USD/mes)
  - Postgres 16 corriendo localmente (sin RDS — ahorra $24/mes)
  - Spring Boot jar (con systemd o supervisor)
  - Caddy 2 como reverse proxy + ACM cert auto (Let's Encrypt)
- 1× Static IP (incluido en el bundle IPv4)
- Snapshot diario automático ($2-5 USD/mes)
- Route53 hosted zone ($0.50 USD/mes)
- ACM cert para custom domain (gratis vía Caddy)

Costo demo: ~$33 USD/mes ≈ $600 MXN/mes
IaC: Terraform en /infra (Lightsail provider de AWS)
CI/CD: GitHub Actions → ssh deploy del jar a la instancia
Dominio: demo.tudominio.com (subdominio del dominio definitivo)
```

**Diferencia con producción**: cuando el primer cliente real cierre, migramos del Lightsail monolítico a Fargate Graviton + RDS Multi-AZ + ALB. La estructura del código y el modelo de datos quedan idénticos — solo cambia el deployment. Lightsail es el "developer preview" de la propuesta.

## Calendario de construcción (5 semanas)

| Semana | Entregable | Definition of Done |
|---|---|---|
| 1 | Backend Spring Boot + DB + endpoints CRUD | curl tests pasan, datos seed cargados, OpenAPI publicado |
| 2 | Web Kiosko PWA + flujo NFC URL + PIN | iPhone real registra evento desde sticker NFC, PWA instalable |
| 3 | Web Admin + SSE real-time + CSV + KPIs | Demo end-to-end funcional en local |
| 4 | AWS infra + dominio + despliegue + monitoreo | Demo accesible en demo.tudominio.com |
| 5 | Pulido visual + ensayo guion + stickers programados | 3 ensayos completos sin fallas |

## Riesgos del demo

| Riesgo | Mitigación |
|---|---|
| AWS cae durante demo presencial | Versión local con ngrok como Plan B, tener URL alterna lista |
| iPhone del prospecto no detecta el sticker | Llevar 2 iPhones: el del prospecto y el tuyo como respaldo |
| Sticker NFC se despega o daña | 10+ stickers programados, repuestos en bolsillo |
| Internet del cliente lento/bloqueado | Hotspot 4G propio dedicado al demo |
| Prospecto pide demo de feature no construida (cálculo horas, biométricos) | Preparar 2 mockups estáticos ("v1.1", "Q3") para mostrar roadmap |

## Próximos pasos al confirmar este plan

1. **Crear estructura del proyecto** Spring Boot + esqueleto de paquetes
2. **Implementar Flyway migrations** del subset del modelo de datos
3. **Implementar endpoints + seed data** con nombres mexicanos realistas
4. **Construir Web Kiosko PWA** con flujo NFC URL
5. **Construir Web Admin** con SSE
6. **Setup Terraform AWS** para deploy
7. **Programar stickers + ensayar guion**

Cada bloque es ~1 semana. Iteramos al final de cada semana.

# assistance-checker

Sistema multi-tenant de control de asistencia para empresas industriales en México. Cumplimiento LFT Art. 804.

Arquitectura, modelo de datos y plan técnico del demo: ver [docs/](./docs/).

## Estructura del repo

```
assistance-checker/
├── backend/           Spring Boot 3.5 + Spring Modulith 1.4 + Postgres 16 + Flyway
├── frontend/          React 18 + Vite + Tailwind + Recharts (kiosko + admin)
├── infra/             Terraform (AWS): modules/{network,database,app} + envs/{staging,prod,demo}
├── .github/           CI (ci.yml), despliegue (deploy.yml), dependabot.yml
├── Dockerfile         Build multi-stage (frontend → bootJar → imagen JRE)
├── scripts/dev.sh     Helper para arrancar todo en local
└── docs/              Documentación del producto y la arquitectura
```

## Quick start (un solo comando)

```bash
./scripts/dev.sh                          # postgres + backend + frontend
# detener:
./scripts/dev.sh stop
```

Servicios al levantar:
- **Backend**: http://localhost:8080
- **Postgres**: localhost:55432 (puerto 5432 ocupado en tu Mac, ver `docker-compose.yml`)
- **Admin web**: http://localhost:5173/admin
- **Kiosko local**: http://localhost:5173/k
- **Kiosko iPhone (mismo wifi)**: http://&lt;tu-mac-ip&gt;:5173/k

PIN universal del demo: `1234`. Tarjetas demo: `04A3B2C1D5E801` a `…E810` (María, Juan, Ana, etc.). Bloqueada: `04A3B2C1D5EBLK`.

## Probar desde tu iPhone

### Opción A — Mismo wifi (más simple, sin HTTPS)

```bash
ipconfig getifaddr en0          # tu IP local, ej. 192.168.1.42
```

En Safari del iPhone:
- Kiosko espera: `http://192.168.1.42:5173/k`
- Simular tap NFC con María: `http://192.168.1.42:5173/k?u=04A3B2C1D5E801`
- Simular tarjeta bloqueada: `http://192.168.1.42:5173/k?u=04A3B2C1D5EBLK`

Limitación: sin HTTPS no funcionan Service Workers (Add to Home Screen no instala bien la PWA), pero el flujo NFC + PIN sí funciona.

### Opción B — ngrok (URL pública HTTPS)

```bash
brew install --cask ngrok        # primera vez
./scripts/dev.sh ngrok           # arranca tunnel
```

ngrok te da una URL como `https://abc123.ngrok-free.app`. Úsala en lugar de la IP local. Funciona desde cualquier red (no necesitas mismo wifi) y como es HTTPS, los Service Workers + Add to Home Screen funcionan correctamente.

Para programar stickers NFC con esa URL: usa la app **NFC Tools** del iPhone → Write → URL → `https://abc123.ngrok-free.app/k?u=04A3B2C1D5E801`.

## Comandos útiles

```bash
# correr solo el backend
cd backend && ./gradlew bootRun

# correr solo el frontend
cd frontend && npm run dev

# build production (emite estáticos del frontend a backend/static, gitignored)
cd frontend && npm run build && cd ../backend && ./gradlew bootJar

# tests de modulith
cd backend && ./gradlew test --tests com.assistance.ModularityTests

# inspeccionar grafo de módulos en runtime
curl http://localhost:8080/actuator/modulith | jq
```

## CI/CD y despliegue

Pipeline completo en GitHub Actions (`.github/workflows/ci.yml` y
`deploy.yml`): lint + build del frontend, tests unitarios/arquitectura del
backend (`./gradlew test`, incluye `ModularityTests` + JaCoCo), tests de
integración con Testcontainers (`./gradlew integrationTest`), escaneo de
seguridad (gitleaks + Trivy) y build/push de la imagen Docker a GHCR. El
despliegue a AWS (ECS Fargate) usa OIDC (sin access keys) y requiere
aprobación manual en el environment `production` de GitHub.

Infraestructura como código en `infra/` (Terraform, AWS): VPC, RDS
Postgres 16 Multi-AZ (prod), ALB con TLS, ECS Fargate, Secrets Manager,
CloudWatch + alarmas. Ver el detalle completo, diagramas, variables/secretos
requeridos y runbooks de rollback/restauración en
[docs/17-ci-cd-y-despliegue.md](./docs/17-ci-cd-y-despliegue.md).

### Variables de entorno de la app (todos los perfiles)

| Variable | Descripción |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` \| `demo` \| `prod` |
| `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD` | Conexión de la app (rol sin privilegios, RLS) |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | Usuario owner que corre Flyway al arrancar |
| `APP_PII_KEY` | Clave de cifrado de columna (CURP/RFC/NSS) |
| `AUTH_ISSUER_URI` | Issuer de Cognito (solo `prod`) |
| `APP_JWT_SECRET` | Solo `local`/`demo`; debe estar vacío en `prod` |
| `CORS_ORIGINS` | Lista de orígenes permitidos |
| `SERVER_PORT` / `MANAGEMENT_PORT` | `8080` (API) / `8081` (actuator: health, info, metrics, prometheus) |

`./scripts/dev.sh` ya exporta valores de desarrollo para todas estas
variables (perfil `local`). `docker compose --profile full up` levanta
Postgres + la app completa en modo `demo` usando el `Dockerfile` de la raíz
(`docker compose up`, sin `--profile full`, sigue levantando solo Postgres
como antes).

## Documentación clave

- [docs/15-demo-mvp-plan.md](./docs/15-demo-mvp-plan.md) — Plan técnico del demo MVP.
- [docs/03-modelo-datos.md](./docs/03-modelo-datos.md) — Modelo de datos completo de producción.
- [docs/02-arquitectura.md](./docs/02-arquitectura.md) — Arquitectura de producción.
- [docs/13-patrones-arquitectonicos.md](./docs/13-patrones-arquitectonicos.md) — Patrones de diseño aplicados.
- [docs/17-ci-cd-y-despliegue.md](./docs/17-ci-cd-y-despliegue.md) — CI/CD, infraestructura y runbooks de despliegue.

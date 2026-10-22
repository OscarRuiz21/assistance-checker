# Asistencia Checker — Documentación de Diseño

Sistema SaaS multi-tenant de control de asistencia para obreros en plantas industriales en México.

## Estado del proyecto

- **Fase**: Diseño cerrado, listo para arrancar implementación.
- **Plataforma**: Java 21 + Spring Boot 3 + PostgreSQL + AWS, hardware ZKTeco K50 Pro.

## Cómo navegar este documento

Lee en este orden si eres **nuevo al proyecto**:

1. [01-vision-y-glosario.md](./01-vision-y-glosario.md) — términos del dominio.
2. [02-arquitectura.md](./02-arquitectura.md) — visión técnica de alto nivel.

Si eres **ingeniero por arrancar a codear**, suma:

3. [03-modelo-datos.md](./03-modelo-datos.md) — schema completo.
4. [06-apis-y-contratos.md](./06-apis-y-contratos.md) — endpoints REST.
5. [13-patrones-arquitectonicos.md](./13-patrones-arquitectonicos.md) — patrones obligatorios del código.
6. [12-push-adapter.md](./12-push-adapter.md) — diseño detallado del Push Adapter.
7. [14-test-architecture.md](./14-test-architecture.md) — pirámide de tests + ArchUnit.
8. [adrs/](./adrs/) — decisiones arquitectónicas justificadas.

Si necesitas el detalle funcional, suma:

9. [04-flujos-edge-cases.md](./04-flujos-edge-cases.md) — políticas operativas.
10. [05-motor-calculo-horas.md](./05-motor-calculo-horas.md) — reglas LFT.
11. [10-cumplimiento-y-seguridad.md](./10-cumplimiento-y-seguridad.md) — LFPDPPP.

## Índice completo

| Doc | Contenido |
|---|---|
| [README.md](./README.md) | Este archivo |
| [01-vision-y-glosario.md](./01-vision-y-glosario.md) | Visión, personas, glosario |
| [02-arquitectura.md](./02-arquitectura.md) | Arquitectura técnica completa |
| [03-modelo-datos.md](./03-modelo-datos.md) | Schema PostgreSQL ~25 tablas |
| [04-flujos-edge-cases.md](./04-flujos-edge-cases.md) | 16 edge cases con políticas |
| [05-motor-calculo-horas.md](./05-motor-calculo-horas.md) | LFT, categorías, JSONB de política |
| [06-apis-y-contratos.md](./06-apis-y-contratos.md) | ~70 endpoints REST |
| [09-hardware-zkteco.md](./09-hardware-zkteco.md) | K50 Pro + spike ADMS + plan B |
| [10-cumplimiento-y-seguridad.md](./10-cumplimiento-y-seguridad.md) | LFPDPPP, encriptación, auth |
| [11-riesgos-y-runbooks.md](./11-riesgos-y-runbooks.md) | Risk register + runbooks operativos |
| [12-push-adapter.md](./12-push-adapter.md) | Diseño detallado del Push Adapter ZKTeco |
| [13-patrones-arquitectonicos.md](./13-patrones-arquitectonicos.md) | Catálogo de patrones del código (hexagonal, SOLID, AOP) |
| [14-test-architecture.md](./14-test-architecture.md) | Pirámide de tests + ArchUnit + Testcontainers |
| [adrs/](./adrs/) | Architecture Decision Records |

## ADRs (decisiones técnicas)

| ADR | Decisión |
|---|---|
| [ADR-001](./adrs/ADR-001-multi-tenancy-shared-db-rls.md) | Multi-tenancy: shared DB + RLS + tenant_id |
| [ADR-002](./adrs/ADR-002-eventos-inmutables-correcciones-append-only.md) | Eventos inmutables, correcciones append-only |
| [ADR-003](./adrs/ADR-003-zkteco-adms-no-sdk.md) | ZKTeco ADMS Push Protocol (no Standalone SDK) |
| [ADR-004](./adrs/ADR-004-no-android-nativo-mvp.md) | Sin app Android nativa en MVP |
| [ADR-005](./adrs/ADR-005-strategy-pattern-extensibilidad.md) | Strategy pattern para extensibilidad |
| [ADR-006](./adrs/ADR-006-arquitectura-aplicacion.md) | Arquitectura de aplicación: services, error handling, AOP, sealed types |

## Convenciones

- **Idioma**: documentación en español MX. Código y nombres de tablas/columnas/campos en inglés (estándar de industria).
- **Markdown**: renderiza en GitHub, Notion, Confluence, GitLab, VS Code preview.
- **Diagramas**: ASCII arte para portabilidad. Mermaid donde aporte claridad.
- **Cambios**: edita el archivo correspondiente, agrega entrada al CHANGELOG si lo creas, o usa PR si ya hay git.

## Audiencia y mantenimiento

Este es un documento **vivo** durante los 3 meses de MVP. Después del go-live, se convierte en documento de referencia. Cada cambio significativo debe:

1. Actualizar el archivo correspondiente.
2. Si toca decisión técnica: agregar nuevo ADR (no editar el existente).
3. Mantener el README sincronizado.

## Contacto y soporte

Definir antes del Sprint 1:
- Tech Lead del proyecto: TBD
- Product Owner: TBD
- Champion del cliente piloto 1: TBD
- Champion del cliente piloto 2: TBD

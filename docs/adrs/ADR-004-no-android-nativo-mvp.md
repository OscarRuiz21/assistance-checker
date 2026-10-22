# ADR-004: Sin app Android nativa en MVP

**Estado**: Aceptado
**Fecha**: 2026-04-27
**Decisores**: Tech Lead

## Contexto

El plan inicial contemplaba una app Android nativa (Kotlin) corriendo en tablets Samsung Galaxy Tab Active 5, con lectores ZKTeco USB para capturar NFC y biometría. Esto implica:

- ~3 person-months de desarrollo Android dedicado.
- Un track de mobile separado del backend y web.
- Mantenimiento futuro de un cliente nativo (publishing, signing, dependency updates).

Con el cambio a hardware ZKTeco K50 Pro standalone con protocolo ADMS Push ([ADR-003](./ADR-003-zkteco-adms-no-sdk.md)), el K50 Pro **es** el kiosko, no requiere app cliente que controlarlo.

## Decisión

**El MVP no incluye app Android nativa.** Los métodos de captura son:

1. **ZKTeco K50 Pro** (producción): hardware standalone con ADMS Push. Captura NFC + PIN.
2. **Web Kiosko Mode** (fallback operativo): la misma webapp admin con ruta full-screen `/kiosko-mode` para PC/tablet en planta sin K50 Pro.

App Android se difiere a:
- **v1.1** si justifica para supervisor (rescue mode, dashboard móvil).
- **v2** si justifica para obrero (geofence, captura desde celular).

## Razonamiento

### Por qué la app Android se vuelve innecesaria con ZKTeco K50 Pro

El K50 Pro es un dispositivo industrial completo:
- UI propia (pantalla TFT 2.4").
- Captura propia (lector NFC + sensor de huella + teclado para PIN).
- Buffer offline propio (~100K eventos local).
- Reloj propio sincronizado vía heartbeat con backend.
- Comunicación HTTP propia (ADMS).

No hay nada que un app Android agregue al hardware K50 Pro que justifique 3 person-months de desarrollo.

### Por qué Web Kiosko Mode como fallback

Hay escenarios donde el K50 Pro no estará presente o fallará:
- **Plantas chicas**: piloto pequeño con 30-50 empleados; un PC + browser puede ser suficiente.
- **Setup inicial**: antes de que llegue/se configure el K50 Pro.
- **Modo rescate**: si el K50 Pro falla, supervisor puede activar PC/tablet con la web kiosko.
- **Ambiente administrativo**: oficinas donde no hay K50 Pro.

Web Kiosko Mode es **gratis** (parte de la webapp admin) y cumple operativamente.

### Limitación conocida: NFC en web

Web Kiosko Mode en iPhone Safari **no puede leer NFC** (Web NFC API solo en Chrome Android). Implicaciones:

- En iPhone: solo PIN entry.
- En Chrome Android: NFC funciona vía Web NFC API.
- En PC: solo PIN entry.

Para casos donde se requiere NFC en web kiosko (planta sin K50 Pro), exigir Chrome Android como navegador del kiosko.

### Beneficios del recorte

```
Plan original:                    Plan revisado:
─────────────                     ─────────────
3 ingenieros (TL/BE/Android+FS)   3 ingenieros (TL/BE/FS)
Android: 3 PM                     Android: 0 PM
SDKs biométricos custom           ZKTeco protocolo nativo
Setup tablet + lector + soporte   Plug-and-play del K50 Pro
                                  
Total ahorrado: ~3 PM
```

Ese person-month de holgura se redistribuye al admin web (más pulido) y al motor de cálculo de horas (mayor margen de iteración).

## Consecuencias

### Positivas

- Equipo de 3 más enfocado: backend + web, no mobile.
- Hardware más barato y robusto (industrial vs tablet con lector).
- Menor superficie de mantenimiento (sin app Android publicada en Play Store).
- UX más consistente (web responsive funciona en cualquier device).

### Negativas

- **Sin captura NFC en iPhone web**. Mitigado: iPhone usa PIN; producción usa K50 Pro que tiene NFC.
- **Sin geofence para captura del obrero** desde celular personal. Difiere a v2.
- **Sin app supervisor móvil nativa**. Web responsive en su celular cubre el caso operativo.

### Cuándo agregar app nativa (v1.1 / v2)

- **v1.1 supervisor app**: si demos quincenales muestran que supervisor necesita más que web responsive (notificaciones push, modo offline robusto).
- **v2 obrero app**: cuando un cliente lo pague o el modelo de negocio lo requiera (ej. obreros remotos, freelancers).

Ambas opciones son strictly aditivas — el backend ya está diseñado para soportarlas.

## Alternativas rechazadas

- **App Android nativa desde MVP**: rechazada por costo de PM no justificado.
- **Flutter cross-platform**: misma decisión; el K50 Pro hace la captura, no necesitamos app que la duplique.
- **PWA con Service Worker**: la web kiosko mode es exactamente eso, sin marketing como "PWA".

## Re-evaluar cuando

- Cliente piloto demanda funcionalidad que solo tendría sentido en app nativa (geofence, push notifications, etc.).
- Crecimiento a clientes con > 10 plantas exige supervisor móvil nativo para ahorrarles fricción.
- v2 incluye obrero móvil con geofence — entonces sí, app nativa o React Native obligatorio.

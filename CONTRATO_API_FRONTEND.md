# Contrato de API — Backend Planificador TASF.B2B

Guía para el frontend: qué endpoints existen, cómo usarlos y qué devuelven.
Generado a partir de los controladores del backend (`EscenarioController`, `ConsultaJobsController`,
`MetadatosController`, `ConfiguracionController`, `AuditoriaController`, `IngestaController`) y sus DTOs.

---

## 1. Generalidades

- **Base URL:** `http://<host>:<puerto>/api/planificador`
- **CORS permitido:** `http://localhost:5173`, `http://localhost:3000`.
- **Formato:** JSON (UTF-8). Las descargas son `text/csv` o `application/zip`.
- **Fechas:** ISO-8601 sin offset, **en UTC** (eje único del sistema), p. ej. `2026-01-01T19:52`.
  Los campos `*Local` son solo etiquetas de hora de pared por ciudad (no son reloj global).
- **Motores (`algoritmo`):** `alns` (default) | `aco`.

> 🔴 **Cambio (eje UTC unificado) — acción requerida en el front.** El cursor de simulación ahora
> avanza en **UTC**, no en hora local. Impacto:
> - **`BloqueSimulacion.horaInicio/horaFin`** (y sus alias `horaInicioUtc/horaFinUtc`) son ahora
>   los **límites UTC de la ventana** y los bloques son **CONTIGUOS**: `horaFin[N] == horaInicio[N+1]`,
>   sin solapes ni huecos. Ya se pueden usar directamente como eje de la animación.
> - **`fechaInicio`** (E1/E2/E3) y **`desde`/`hasta`** de `/demanda/resumen` deben enviarse **en UTC**.
> - **`primeraVentana`/`ultimaVentana`** de `/dataset/info` se exponen en UTC (la primera pasa de
>   `2026-01-02T00:02` local a ≈ `2026-01-01T19:52` UTC).
- **Patrón de uso:** las corridas largas son **asíncronas** (devuelven un `jobId`). El front
  hace **polling** de estado/bloques. No hay WebSocket/SSE.

> 🔴 **Cambio (paginación anti-OOM) — acción requerida en el front.** `/jobs/{id}/vuelos/carga` y
> `/jobs/{id}/almacenes/ocupacion` ya **no** devuelven todo de una vez: son **paginados** por
> `desde`/`limit`. El front debe recorrer páginas empezando en `desde=0` y, mientras la respuesta traiga
> `hayMas:true`, volver a pedir con `desde=proximoDesde` (para refrescar, reiniciar en `desde=0`).
> `total` pasa a ser "filas en esta página". `limit` se clampea al tope del servidor (5000). Si el front
> no se actualiza, una llamada simple seguirá funcionando pero **solo verá la primera página**.
> Además, `/jobs/{id}/indicadores` ahora es un **snapshot reciente acotado** (no histórico) y
> `/demanda/resumen` **acota el rango** a un span máximo (reporta el rango efectivo en `desde`/`hasta`).

### Estados de un job (`estado`)
`encolado` → `calentando` (opcional) → `ejecutando` → **`completado`** | `cancelado` | `error`.

### Convención de códigos HTTP
| Código | Significado en esta API |
|---|---|
| `200 OK` | Respuesta con cuerpo. |
| `202 Accepted` | Job asíncrono creado (lanzamiento de escenario). |
| `204 No Content` | Aún no disponible (job ejecutando) **o** fin de stream (E1 ventana). |
| `400 Bad Request` | Parámetros inválidos / E1 no inicializado. |
| `404 Not Found` | `jobId` o recurso inexistente. |
| `409 Conflict` | El job ya terminó / no está activo (p. ej. `cancelar-vuelo` sobre un job finalizado); **o** conflicto ingesta↔simulación: lanzar un escenario con una ingesta en curso, o iniciar una ingesta con una simulación activa (§9); **o** pedir la `auditoria.zip` de un job aún activo o cuya solución ya fue reemplazada por otra corrida (§8). |

> **Cuerpo de error uniforme** (respuestas `400` y `500`, vía `GlobalExceptionHandler`):
> `ErrorResponse` = `{ "error", "mensaje", "estado", "timestamp", "path" }`. `error` y `mensaje`
> llevan el mismo texto legible; la clave **`error` se conserva** por compatibilidad (el front puede
> seguir leyendo `body.error`). Los `404` y `409` mantienen su cuerpo propio por endpoint y no pasan
> por el handler.

---

## 2. Flujos típicos

### Escenario 2 (período) y Escenario 3 (hasta colapso) — asíncronos
1. `POST /escenario2/iniciar` (o `/escenario3/iniciar`) → recibes `jobId`.
2. Polling cada ~2–5 s:
   - `GET /jobs/{jobId}/estado` → `estado`, `progreso`, `alertaColapso`.
   - `GET /jobs/{jobId}/bloques?desde=N` → bloques nuevos para dibujar (incremental).
   - `GET /jobs/{jobId}/alerta-colapso` → alerta de colapso inminente (VERDE/AMBAR/ROJO).
3. Cuando `estado == "completado"` (o `bloques.terminado == true`):
   - `GET /jobs/{jobId}/resultado` → `SimulacionResponse` final con métricas.
   - Bajo demanda: `GET /jobs/{jobId}/auditoria.zip` (se **genera al pedirlo**, opcionalmente por
     rango de fechas; incluye el CSV de vuelos cancelados — ver §8). Atajos: `…/auditoria/dia?fecha=`
     (un día) y `…/auditoria/estimacion?desde=&hasta=` (cuántos CSV habrá, sin generar el ZIP).

### Escenario 1 (día a día) — job asíncrono
Mismo patrón que E2/E3: `POST /escenario1/iniciar` → `jobId` → polling de
`/jobs/{jobId}/estado` y `/jobs/{jobId}/bloques?desde=N`.

> ⚠ El antiguo modo **incremental** de E1 (`/escenario1/inicializar`, `/escenario1/ventana`,
> `/escenario1/estado`, `/escenario1/bloque/{index}`, `/escenario1/cancelar-vuelo`) fue
> **ELIMINADO**. E1 corre únicamente como job; las cancelaciones en vivo van por
> `POST /jobs/{jobId}/cancelar-vuelo`.

### Escenario 1 — OPERACIÓN día a día en vivo (`enVivo=true`) — **no es simulación**
Modelo "caja registradora": el software en uso real. La demanda NO sale del dataset, entra **EN VIVO**.
1. **Logística** arranca la operación: `POST /escenario1/iniciar?enVivo=true` → `jobId` (compartirlo).
2. **Registradores** (Lima / Buenos Aires / Copenhague / Delhi) dan de alta envíos **en paralelo** con
   `POST /jobs/{jobId}/registrar-envios` (data-entry). Opcional: `POST /jobs/{jobId}/cargar-envios-txt`.
3. **Logística** observa el mapa con el mismo polling (`/bloques?desde=N`, `/estado`, telemetría).
4. Al terminar: `POST /jobs/{jobId}/cancelar`.
> Detalle de los endpoints y la concurrencia en §4 → "E1 — Operación día a día en vivo".

---

## 3. Catálogo y datos estáticos

### `GET /dataset/info`
Metadatos del dataset. Útil para validar `fechaInicio` antes de lanzar.
```json
{
  "primeraVentana": "2026-01-01T19:52", "ultimaVentana": "2029-01-05T23:36",
  "diasDisponibles": 1099,
  "totalEnvios": 9519995, "totalMaletas": 9519995, "totalMaletasIndividuales": 17876907,
  "totalAeropuertos": 30, "totalVuelos": 2866
}
```
> **`primeraVentana`/`ultimaVentana` están en UTC** (eje único del sistema): son el `MIN`/`MAX` del
> `registroUtc` de los envíos (`fecha_hora_registro` local − offset del origen). El cursor de
> simulación avanza en UTC, así que estos valores definen el rango global de la línea de tiempo y
> son la referencia para elegir/validar `fechaInicio` (**que también va en UTC**, ver §Escenarios).
> Por eso la primera ventana cae el `2026-01-01T19:52` UTC (primer envío Karachi/Delhi, GMT+5),
> y no en la hora de pared local `2026-01-02T00:02`.

### `GET /aeropuertos`
Mapa estático `{ [codigo]: AeropuertoDTO }` (cacheable, `max-age=3600`). Para dibujar el mapa.
```json
{ "SKBO": { "codigo": "SKBO", "latitud": 4.70, "longitud": -74.14,
            "capacidadAlmacen": 1500, "capacidadAlmacenOriginal": 1000, "gmt": -5.0 } }
```
> **`capacidadAlmacen`** es la capacidad ACTUAL (puede haber sido modificada por el operador vía
> `PUT /configuracion/aeropuertos/{icao}/capacidad`); **`capacidadAlmacenOriginal`** es el valor
> original de fábrica. Cada corrida resetea la actual al original al arrancar (las modificaciones son
> **por corrida**). Ver §Configuración de capacidades.
> **`gmt`** (number, con signo, en horas) es el offset horario respecto a UTC: el **mismo** valor que
> el backend usa para normalizar a UTC. El front lo usa tanto para mostrar el reloj local del
> registrador como para convertir local→UTC, de modo que front y back comparten un único huso (con
> esto el front retira su fallback curado). El dataset solo trae husos **enteros**, así que hoy `gmt`
> no tiene fracción: `SPIM -5`, `SABE -3`, `EKCH +2`, `VIDP +5` (Delhi va como **+5**, no +5:30; el
> tipo `number` admitiría `:30`/`:45` si el dato fuente los tuviera). En la operación día a día, el
> registrador de Copenhague usa **+2** y el de Delhi **+5**, alineados a este valor.

### `GET /vuelos`
Catálogo estático de la red completa de vuelos planeados (`VueloBackend[]`, ~2.866; cacheable,
`max-age=3600`). Espejo de `/aeropuertos` para los nodos: permite pre-dibujar TODAS las aristas
de la red al cargar la app, sin esperar a `/jobs/{id}/resultado` (que solo llega al final).
`fechaSalida`/`fechaLlegada` son horarios de plantilla base; los horarios reales por día llegan
en los tramos UTC de cada bloque, y la carga real por vuelo en `cargasVuelos` / `/vuelos/usados`
(aquí `cargaAsignada` es siempre 0).
```json
[ { "id": "SKBO-SEQM-08:30", "origen": "SKBO", "destino": "SEQM",
    "fechaSalida": "2026-01-01T08:30", "fechaLlegada": "2026-01-01T10:15",
    "capacidadMaxima": 450, "capacidadMaximaOriginal": 300, "cargaAsignada": 0 } ]
```
> **`capacidadMaxima`** es la capacidad ACTUAL (modificable vía
> `PUT /configuracion/vuelos/{idVuelo}/capacidad`); **`capacidadMaximaOriginal`** es el valor
> original del TXT (destino del botón restaurar). Ver §Configuración de capacidades.

### Configuración de capacidades (edición en frío / en caliente + restaurar)
Endpoints para que el operador ajuste capacidades de aeropuertos y vuelos. Hay **tres niveles** de
valor: *original de fábrica* (el del TXT, intocable), *baseline en frío* (lo configurado, persiste) y
*override en caliente* (efímero, solo el run en curso).

El **modo se decide automáticamente** según si hay una simulación corriendo (el front NO envía ningún
parámetro extra):

- **Sin job en curso ⇒ EN FRÍO**: el cambio persiste (BD + memoria). El próximo job arranca con ese valor.
- **Con job en curso ⇒ EN CALIENTE**: el cambio afecta solo a esa corrida (los bloques siguientes) y se
  descarta al iniciar el próximo job, que vuelve al baseline en frío.

| Método | Ruta | Query | Éxito | Errores |
|---|---|---|---|---|
| PUT | `/configuracion/aeropuertos/{icao}/capacidad` | `valor` (int ≥ 1) | `200` (sin cuerpo) | `400` valor<1, `404` icao inexistente |
| PUT | `/configuracion/vuelos/{idVuelo}/capacidad` | `valor` (int ≥ 1) | `200` (sin cuerpo) | `400` valor<1, `404` id inexistente |
| POST | `/configuracion/capacidades/restaurar` | — | `200` (sin cuerpo) | — |

> **Botón restaurar** (`POST /configuracion/capacidades/restaurar`): devuelve TODAS las capacidades
> (aeropuertos y vuelos) a su valor original de fábrica en BD + memoria, con efecto inmediato en el job
> en curso si lo hay. Es el modo de deshacer tanto lo frío como lo caliente.
>
> `idVuelo` puede llegar con o sin los dos puntos de la hora (`SKBO-SEQM-08:30` o `SKBO-SEQM-0830`);
> el backend normaliza. Ejemplo: `PUT /configuracion/aeropuertos/SKBO/capacidad?valor=1500`.
>
> ⚠ **Colapso por sobre-suscripción**: si reduces una capacidad por debajo de la ocupación concurrente
> actual (p. ej. un almacén al 130% de la nueva capacidad), el detector de colapso de almacén se dispara
> y **detiene la corrida** en el siguiente bloque (`utilización > 100%`). Es el comportamiento esperado.
> Además, la utilización reportada puede exceder el 100% mientras dura la sobrecarga: el front debe tolerarlo.

### Modificación de horarios de vuelo (solo EN FRÍO) — prueba E1 "día a día"
Ajusta la hora de salida/llegada (y por ende la duración) de un vuelo **existente** del dataset. Pensado
para adaptar los horarios de los vuelos del caso "según la hora de presentación", **antes de iniciar** la
corrida. A diferencia de la capacidad, el horario **solo se modifica sin simulación en curso** (con un job
activo devuelve `409`): en caliente el equivalente seguro es **cancelar el vuelo-día + `agregar-vuelo`**
con el horario nuevo.

| Método | Ruta | Query | Éxito | Errores |
|---|---|---|---|---|
| PUT | `/configuracion/vuelos/{idVuelo}/horario` | `salida` y/o `llegada` (LOCAL `"HH:mm"`, al menos una) | `200` `{idVuelo, aplicado:true}` | `400` hora malformada / sin params, `404` id inexistente, `409` simulación en curso |
| POST | `/configuracion/vuelos/restaurar-horarios` | — | `200` `{restaurados:N}` | `409` simulación en curso |

> ⚠ **El `idVuelo` se RENOMBRA si cambia la salida**: el invariante del sistema es
> `id_vuelo ≡ ORIGEN-DESTINO-HHMM(salida)`. La respuesta trae el **id resultante** en `idVuelo` — el front
> debe usarlo a partir de ese momento (p. ej. `SPIM-SCEL-0859` con `salida=11:00` pasa a `SPIM-SCEL-1100`).
> Las horas son **LOCALES** del origen/destino (como el TXT del dataset); la duración UTC la deriva el
> backend con `floorMod` 24 h (soporta cruces de medianoche y duraciones de 4–13 h).
>
> `restaurar-horarios` devuelve el horario **y el id** de todos los vuelos modificados a su valor de
> fábrica. Es independiente del botón `restaurar` de capacidades.
>
> ⚠ **Costo operativo**: modificar un horario **invalida la caché de esqueletos** de ruteo persistida
> (su huella incluye los horarios), así que el **arranque del siguiente job paga el pre-warm frío**
> (~13 min en la VM de 2 vCPU). Hazlo con margen antes de la hora de la prueba. La vía *agregar vuelos*
> (`cargar-vuelos-txt`, en caliente) **no** tiene este costo.

### 🟦 Resumen para el front — manejo de los cambios E1 «día a día»

Checklist accionable de qué cambia para el front en la prueba de operación día a día. **Regla de oro:
lo que es EN FRÍO va ANTES de iniciar el job; lo EN CALIENTE va DESPUÉS (requiere job activo).**

1. **Hora de los envíos — dos vías, dos convenciones (no mezclar):**
   - `POST /jobs/{id}/registrar-envios` (JSON, data-entry manual): el **front convierte a UTC** y manda
     `fechaHoraRegistro` en UTC. *Sin cambios respecto de hoy.*
   - `POST /jobs/{id}/cargar-envios-txt` (archivos por sede): el archivo trae **hora LOCAL de la sede** y
     **el backend la convierte** con el `gmt` del ICAO origen. El front **NO** debe pre-convertir el TXT.
     Verificable en `/estado.enviosInyectados[].readyTimeUtc` (p. ej. VIDP +5, `02:00` local → `…T21:00`
     del día anterior en UTC).
2. **`PUT …/vuelos/{id}/horario` renombra el id**: tras un cambio de salida, **usa el `idVuelo` que
   devuelve la respuesta** (`SPIM-SCEL-0859` → `SPIM-SCEL-1100`). Si el front cachea la lista de vuelos,
   debe refrescar ese id. Solo EN FRÍO (con job activo → `409`). Hazlo **antes de iniciar** y con margen
   (dispara pre-warm frío en el arranque del job).
3. **`POST …/cargar-vuelos-txt` responde `202 = ENCOLADO`, no aplicado.** El front debe:
   - Renderizar `detalleDescartados` (líneas ignoradas con `linea`/`contenido`/`motivo`) para el operador.
     Los **duplicados y basura NO son error del lote** — el `202` puede traer `encolados:0`.
   - **Confirmar la aplicación con polling de `/estado`** (`vuelosAgregados` / `altasVueloNoAplicadas`):
     los vuelos aplican en la **siguiente frontera de bloque**, que en E1 **enVivo llega en tiempo real**
     (hasta `Sa` minutos después). No asumir aplicación inmediata tras el `202`.
4. **Capacidad 999 y restauraciones son EN FRÍO** (sin job): `PUT …/aeropuertos/{icao}/capacidad?valor=999`
   antes de iniciar; `POST …/capacidades/restaurar` y `POST …/vuelos/restaurar-horarios` para volver a
   fábrica (ambos `409` con job en curso).
5. **Los envíos inyectados usan id sintético `INV-…`** (el del TXT/JSON se descarta); son por-corrida (no
   entran al dataset maestro) y las altas en caliente se **revierten al iniciar la corrida siguiente**.

Secuencia operativa completa en `docs/runbook-e1-dia-a-dia.md` (verificada e2e).

### `GET /escenarios`
Catálogo con defaults (Sa, Ta, K por escenario), motores soportados y endpoints de cada escenario.
No requiere hardcodear nada en el front.

---

## 4. Lanzamiento de escenarios

> `seed` es opcional (reproducibilidad). **Las cancelaciones de vuelo ya no son aleatorias**: se
> ordenan en vivo durante la corrida con `POST .../cancelar-vuelo` (ver §5). No existe el parámetro
> `cancelProb`.

> **K es FIJO por escenario (regla de negocio): E1=1, E2=144, E3=144.** No se puede cambiar
> por request: el parámetro `k` de E2/E3 se acepta solo por compatibilidad y, si llega con un
> valor distinto al fijo, el endpoint responde `400` (`{"error":"k es fijo en el escenario 2:
> 144 (recibido: X)"}`). El catálogo `GET /escenarios` lo señala con `"kFijo": true`.

### `POST /escenario2/iniciar` → `202`
Query params (todos opcionales):
| Param | Tipo | Default | Notas |
|---|---|---|---|
| `k` | int | — | **FIJO en 144**; opcional, solo verificación (400 si ≠ 144). |
| `algoritmo` | string | `alns` | `alns` \| `aco`. |
| `seed` | long | aleatorio | reproducibilidad. |
| `fechaInicio` | ISO datetime **UTC** | primera ventana | inicio del período (eje UTC). |
| `sa` | int | yaml | override de Sa (min). |
| `ta` | int | yaml | override de Ta (s). |
| `dias` | int | yaml | duración en días (calcula nº de bloques). |
| `procesamientoPrevio` | bool | **forzado a false** | warm-up desactivado. |

Respuesta: `{ "jobId", "escenario":"2", "algoritmo", "k":144, "seed", "estado", ... }`.
`400` si `k ≠ 144`, `sa ≤ 0`, `ta ≤ 0` o `fechaInicio` fuera del rango del dataset
(`{ "error": "..." }`).

> ⚠ **E2 con `fechaInicio` arranca EN FRÍO** (decisión vigente): el warm-up está desactivado
> a propósito (`procesamientoPrevio` no tiene efecto), así que NO hay aviones en el aire ni
> almacenes ocupados al inicio del período visible. Si se necesita estado realista en una
> fecha posterior, usar **E1 o E3 con `fechaInicio`**, que sí pre-calculan (ver abajo).

### `POST /escenario3/iniciar` → `202`
Params: `k` (**FIJO en 144**; opcional, 400 si ≠ 144), `umbralColapso` (0.20, legacy: el
colapso real lo disparan almacén lleno y backlog vencido), `algoritmo` (alns), `seed?`,
**`fechaInicio?`** (ISO datetime **UTC**).
Respuesta incluye además `umbralColapso` y `k:144`. El E3 **se detiene** al primer colapso
logístico. `400` si `k ≠ 144` o `fechaInicio` fuera del rango del dataset.

> **E3 recorre hasta el colapso (o hasta el fin del dataset).** A diferencia de E1/E2 (acotados por
> `max-ventanas`/`dias`), el horizonte de E3 es **todo el dataset** desde `fechaInicio` (perilla
> `planificador.scenario.max-ventanas-colapso=0`); la corrida solo termina antes si se dispara el
> colapso. Por eso `totalBloques` de un E3 puede ser grande (p. ej. ~2.200 con K=144 sobre los 3 años):
> es el tope si nunca colapsara, no un número que vaya a recorrerse siempre.

### `POST /escenario1/iniciar` → `202` (E1 como job asíncrono)
Params: `algoritmo` (alns), `seed?`, **`fechaInicio?`** (ISO datetime **UTC**), **`enVivo?`** (bool,
default `false`). K se fija al default día-a-día. `400` si `fechaInicio` está fuera del rango del
dataset (**solo** cuando `enVivo=false`). La respuesta incluye `"enVivo"`.

> 🟢 **`enVivo=true` = OPERACIÓN día a día (no simulación).** Arranca la "caja registradora": la demanda
> NO se lee del dataset, entra EN VIVO por registro de empleados + carga TXT; el cursor se ancla a
> `now()` UTC y avanza en tiempo real hasta `POST /jobs/{id}/cancelar`. En este modo `fechaInicio` se
> ignora. **Ver "E1 — Operación día a día en vivo" al final de esta sección.**

> **`fechaInicio` en E1/E3 — pre-cálculo (warm-up Ta-only):** si `fechaInicio` es posterior a
> la primera ventana del dataset, el período previo se simula como warm-up: cada bloque
> respeta el presupuesto **Ta** (cota dura de cómputo) pero **ignora el sleep de Sa**, así el
> job llega rápido a `fechaInicio` con estado realista (aviones en el aire, almacenes y
> backlog poblados). Mientras dura, `estado="calentando"` (progreso en `bloqueWarmup/`
> `totalBloquesWarmup`); los bloques del warm-up NO se publican. Desde `fechaInicio` la fase
> visible respeta Sa con normalidad. Los aviones que quedan en el aire al llegar a
> `fechaInicio` se consultan en `GET /jobs/{id}/estado-inicial` (ver §5).

### Síncronos (bloquean hasta terminar — solo para pruebas / corridas cortas)
- `GET /ejecutar?algoritmo=alns&k=14` → `SimulacionResponse` (solo `alns`).
- `GET /ejecutar-colapso?k=144&umbralColapso=0.20` → `SimulacionResponse`.
- `GET /bloque/{index}` → `BloqueSimulacion` del último `/ejecutar` síncrono (o `404` si el índice
  no existe). Legacy: en el flujo asíncrono se usa `GET /jobs/{jobId}/bloques?desde=N` (§5).

### E1 — Operación día a día en vivo (`enVivo=true`)

Con `POST /escenario1/iniciar?enVivo=true` el job es la **operación real** (no simulación). La demanda
entra 100% en vivo por estos endpoints (requieren el job **activo**); todo se persiste en
`envio_inyectado` (tabla que se limpia al arrancar cada corrida) y **nunca** toca el dataset maestro
`ENVIO`. Los registros aplicados se ven en `enviosInyectados` de `GET /jobs/{id}/estado` (§5).

#### `POST /jobs/{jobId}/registrar-envios`  *(alias de `POST /jobs/{jobId}/inyectar-envios`)*
Registro manual (data-entry). Valida **todo el lote o nada**. **Body JSON** (`InyeccionEnviosRequest`):
```json
{
  "envios": [
    {
      "origen": "SPIM", "destino": "EKCH", "cantidad": 30,
      "fechaHoraRegistro": "2026-06-28T19:00",   // UTC; opcional → próximo bloque
      "clienteId": 7,                            // opcional
      "registrador": "Ana Pérez",                // opcional (E1 operación)
      "sede": "Lima"                             // opcional (E1 operación)
    }
  ]
}
```
- El front toma la hora local del registrador y la **convierte a UTC** en `fechaHoraRegistro`. Si se
  omite, el envío entra en el próximo bloque.
- **202** → `{ "jobId", "encolado": true, "encolados": N }`.
- **404** job inexistente · **409** job no activo · **400** lote vacío / ICAO desconocido /
  origen=destino / cantidad ≤ 0 (`{ "error": "..." }`).

> El endpoint `POST /jobs/{jobId}/inyectar-envios` es el mismo (alias semántico). Sirve también para
> inyectar envíos en vivo durante E2/E3.

#### `POST /jobs/{jobId}/cargar-envios-txt` — carga TXT de envíos adicionales (`multipart/form-data`)
Campos del formulario:

| Campo | Cardinalidad | Contenido |
|---|---|---|
| `archivos` | **1..N archivos** | TXT con `id-AAAAMMDD-HH-MM-ICAOdestino-maletas-cliente` por línea (mismo formato de §9) |
| `origen` | opcional | ICAO de origen para todos los archivos; si se omite, se deriva del nombre `_envios_<ICAO>_.txt` |
| `registrador` | opcional | se aplica a todos los ítems |
| `sede` | opcional | se aplica a todos los ítems |

- ⚠ **La fecha-hora del TXT está en hora LOCAL de la sede origen** (no en UTC): el **backend la convierte
  a UTC** con el `gmt` del ICAO origen (`registroUtc = local − offset`), como los TXT del dataset maestro.
  Esto difiere de `registrar-envios` (JSON), donde el **front** envía `fechaHoraRegistro` ya en UTC. Se
  parsea sin tocar la BD y se delega en la **misma** cola/validación/persistencia que el registro manual.
- **202** → `{ "jobId", "encolado": true, "encolados": N }`.
- **404** job inexistente · **409** job no activo · **400** faltan archivos / sin ICAO derivable /
  **ICAO origen desconocido** / ningún envío válido / algún envío inválido.

#### `POST /jobs/{jobId}/cargar-vuelos-txt` — carga TXT de planes de vuelo **en caliente** (`multipart/form-data`)
Alta masiva de vuelos adicionales durante la corrida (equivalente por lotes de `agregar-vuelo`, ver §5).
Un solo campo de formulario `archivos` (**1..N**), cada TXT con el formato del dataset
`ORIG-DEST-HH:MM-HH:MM-CAPACIDAD` por línea, **horas LOCALES**.

- Líneas de **comentario** (`*`, `**`, `//`), vacías y la **cabecera** `ORIG-DEST` se ignoran en silencio.
- Cada línea válida se **encola** por la misma tubería que `agregar-vuelo`: vuelo **efímero** por corrida,
  **recurrente diario**, aplicado en la **frontera del siguiente bloque** (≤ `Sa`). Se ve en `/estado`
  (`vuelosAgregados` / `altasVueloNoAplicadas`).
- **Duplicados tolerados** ("no se preocupe si coincide con un vuelo existente") y líneas inválidas se
  **descartan por línea** y se reportan — **no abortan** el lote. Por eso el `202` puede traer `encolados:0`.
- **202** → `{ "jobId", "encolado": true, "encolados": N, "descartados": M, "detalleDescartados": [ { "archivo", "linea", "contenido", "motivo" } ] }`.
- **404** job inexistente · **409** job no activo (`{ "encolado": false, "motivo" }`) · **400** faltan
  archivos / ninguna línea de vuelo en los archivos.

> Modificar el horario de un vuelo **ya existente** es otra cosa (ver `PUT /configuracion/vuelos/{id}/horario`
> arriba): eso es **EN FRÍO** (antes de iniciar) y **renombra** el id; `cargar-vuelos-txt` **agrega** vuelos
> nuevos **en caliente** sin tocar la caché de esqueletos.

#### Concurrencia y husos (garantías del backend)
- Varios registradores POST en paralelo: cada request en su hilo; la validación es previa al encolado y
  la cola es lock-free (thread-safe) → sin carreras entre registros simultáneos.
- Un solo worker consume la cola en orden de cursor UTC y rutea secuencialmente (sin contención de
  capacidades). Los ids `INV-bloque-n` los asigna el worker → sin colisión.
- Cada envío lleva su ICAO origen; el orden temporal correcto entre sedes lo da el eje UTC común. Los
  husos los expone `GET /aeropuertos` (`gmt`) y son los **enteros del dataset**: Lima −5, Buenos Aires
  −3, Copenhague **+2**, Delhi **+5**. En la vía **JSON** (`registrar-envios`) el front toma ese `gmt`,
  convierte la hora local del registrador a UTC y el backend la ordena en el mismo reloj — front y back
  usan exactamente el mismo offset. En la vía **TXT** (`cargar-envios-txt`) la hora del archivo es LOCAL
  y **el backend** aplica ese mismo `gmt` para convertirla a UTC.

---

## 5. Seguimiento del job

### `GET /jobs?activos=true`
Lista de jobs en memoria (por defecto solo activos). Para re-enganchar tras un refresh.
```json
{ "jobs": [ { "jobId","escenario","algoritmo","estado","enVivo","k","seed","progreso", ... } ], "total": 1 }
```
> **`enVivo`** (bool) distingue la **operación día a día** (E1 con `enVivo=true`) de un E1 de
> simulación. El front lo usa para auto-detectar la operación en curso tras un refresh; si por lo que
> fuera no llegara, cae a filtrar por `escenario === "1"` activo (menos preciso).

### `GET /jobs/activo` — **reenganche en un solo round-trip (F5 / cliente nuevo)**
El backend alimenta a varias páginas/usuarios con la misma simulación: al cargar la página (F5 o una
computadora nueva) el front llama **primero** aquí. Devuelve **siempre 200**: el job en curso (a lo
sumo hay uno ejecutando; si solo hay encolados, el próximo a ejecutar) o `activo=false`.
```json
{ "activo": true, "jobId": "...", "escenario": "2", "algoritmo": "aco", "estado": "ejecutando",
  "enVivo": false, "progreso": 0.35, "totalBloques": 100, "primerBloqueDisponible": 65,
  "temporizadorInicioUtc": "2026-07-03T15:02:11Z", "duracionRealMs": 754000 }
```
Sin simulación en curso: `{ "activo": false }` (el resto de campos se omite).
> **Flujo de reenganche recomendado:** si `activo`, pintar el estado actual con `/dashboard` +
> `/indicadores` (+ `/estado-inicial` si aplica) y arrancar el polling de `/bloques` **con
> `desde = totalBloques`** — así el mapa retoma en la punta y nunca pide el rango ya purgado del
> buffer (ver `/bloques`). **NO** arrancar con `desde=0` tras un refresh en una corrida larga.
>
> **`temporizadorInicioUtc` / `duracionRealMs`** — temporizador REAL de la simulación, almacenado en
> el backend para que todos los clientes vean el mismo valor: arranca al publicarse el **primer
> bloque** y `duracionRealMs` (lo calcula el servidor en cada respuesta) se congela al terminar el
> job. El front solo lo formatea (754000 ⇒ `12:34`) y puede hacerlo avanzar localmente entre polls,
> re-sincronizando con cada respuesta. `null`/omitidos mientras no haya primer bloque.

### `GET /jobs/{jobId}/estado`
Estado y progreso. **Incluye `alertaColapso`** (ver §7) cuando existe.
```json
{
  "jobId":"...", "escenario":"2", "algoritmo":"aco", "k":144, "seed":123,
  "estado":"ejecutando", "bloqueActual":42, "totalBloques":120, "progreso":0.35,
  "bloqueWarmup":0, "totalBloquesWarmup":0, "progresoWarmup":0.0,
  "posicionEnCola":0, "canceladoPorUsuario":false, "taPromedioMs":10000,
  "temporizadorInicioUtc":"2026-07-03T15:02:11Z", "duracionRealMs":754000,
  "inicio":"2026-06-08T20:46:24", "fin":null, "error":null,
  "alertaColapso": { "nivel":"AMBAR", "mensaje":"almacén SEQM al 88% de capacidad", "bloque":42, ... },
  "vuelosCancelados": [ { "origen":"SKBO", "destino":"SEQM",
                          "fechaHoraSalida":"2026-01-03T14:30", "enviosAfectados":7 } ],
  "cancelacionesNoAplicadas": [],
  "enviosInyectados": [ { "idEnvio":"INV-3-0", "origen":"SPIM", "destino":"EKCH", "cantidad":30,
                          "clienteId":7, "slaHoras":48, "readyTimeUtc":"2026-06-28T19:00",
                          "bloqueIdx":3, "registrador":"Ana Pérez", "sede":"Lima" } ]
}
```
> `vuelosCancelados` lista las cancelaciones **ya aplicadas** por el motor (en orden de
> aplicación), con la cantidad de envíos devueltos al backlog por cada una. Sirve para
> marcar/retirar del mapa los vuelo-días cancelados sin esperar la auditoría final.
> `fechaHoraSalida` está en **UTC** (el mismo eje que el body de `POST /cancelar-vuelo` y que
> `fechaSalida` de `/vuelos/usados`).
>
> `cancelacionesNoAplicadas` lista las órdenes que el motor **no pudo aplicar** porque no casó
> ningún vuelo-día (trayecto inexistente o `fechaHoraSalida` fuera del eje UTC esperado). Se emite
> siempre (vacía si no hubo ninguna); permite al front avisar que la cancelación no surtió efecto
> en vez de fallar en silencio.
>
> `enviosInyectados` lista los envíos agregados **EN VIVO** ya aplicados (registro manual, carga TXT o
> inyección), en orden de entrada, con su id sintético `INV-bloque-n`, `readyTimeUtc` (UTC) y —en la
> operación E1— `registrador`/`sede`. Es el registro operativo del día a día.
>
> `temporizadorInicioUtc`/`duracionRealMs` — el temporizador real de la simulación (mismos campos y
> semántica que en `GET /jobs/activo`): arranca con el primer bloque publicado, lo calcula el
> servidor y se congela al terminar. Omitidos mientras no haya primer bloque.

### `GET /jobs/{jobId}/estado-inicial` — **aviones ya en el aire al inicio (jobs con warm-up)**
Snapshot del estado al llegar a `fechaInicio` tras el pre-cálculo (E1/E3 con `fechaInicio`):
las asignaciones del warm-up cuyos envíos siguen **activos** en ese instante — en vuelo, en
escala o con tramos aún por salir — con sus `tramos[]` UTC completos.
```json
{ "jobId":"...", "fechaInicio":"2026-03-15T00:00", "total":42,
  "asignaciones": [ /* AsignacionMaleta[] (mismo esquema que en los bloques) */ ] }
```
- `204` mientras el job está `encolado`/`calentando` (aún calculando) · `404` si no existe.
- Lista **vacía** si el job no tuvo warm-up (sin `fechaInicio`, o E2 — que arranca en frío).
- Uso: pintar estos envíos con la MISMA lógica de interpolación de §10 ANTES de consumir los
  bloques; los entregados durante el warm-up no aparecen, y los sinRuta del warm-up
  reaparecerán por los bloques visibles cuando el backlog los enrute.

### `GET /jobs/{jobId}/bloques?desde=N` — **stream incremental (clave para dibujar)**
Devuelve los bloques publicados desde el índice `N`.
```json
{ "bloques": [ /* BloqueSimulacion[] */ ], "total": 43, "primerBloqueDisponible": 8,
  "duracionRealMs": 754000, "terminado": false }
```
`terminado` = el job ya no está `encolado`/`calentando`/`ejecutando`. Patrón: guardar `total`
recibido y volver a pedir con `desde = total`.
> **`desde` purgado ⇒ `bloques: []` (cambio anti-desincronización).** Los bloques viejos se purgan
> del buffer en RAM (`max-bloques-buffer`); `primerBloqueDisponible` indica el índice del primer
> bloque aún retenido. Si `desde < primerBloqueDisponible`, el backend devuelve `bloques` **vacío**
> — ya **NO** realinea en silencio ni suelta la ventana retenida (eso hacía que un front que
> recargaba con `desde=0` animara bloques históricos mezclados con los nuevos). **Resync del
> cliente:** si `bloques` llega vacío y `desde < primerBloqueDisponible`, saltar a la punta con
> `desde = total` (o `desde = primerBloqueDisponible` si se quiere repintar la ventana retenida,
> a sabiendas de que son bloques pasados). Cada `BloqueSimulacion` trae su `bloqueIdx` absoluto:
> úsalo para verificar continuidad en vez de contar respuestas.
>
> `duracionRealMs` = temporizador real de la simulación (ver `GET /jobs/activo`); viene en cada poll
> para que las páginas del mapa lo mantengan al día sin llamadas extra. Omitido hasta el primer bloque.

### `GET /jobs/{jobId}/alerta-colapso`
Alerta de colapso **inminente** (pre-colapso). Siempre responde (VERDE si no hay riesgo). Ver §7.

### `GET /jobs/{jobId}/resultado?incluirVuelosPlaneados=true`
`SimulacionResponse` final. `204` si el job sigue ejecutando.
> **`incluirVuelosPlaneados`** (opcional, default `true`): con `false`, la respuesta omite la lista
> `vuelosPlaneados` (llega como `null`). Esa lista es grande (miles de `VueloBackend`) y lenta de
> serializar; pedir el resultado sin ella devuelve un payload mucho menor y más rápido cuando el
> cliente no necesita los vuelos planeados (p. ej. solo quiere `metricas`). Sin el parámetro, el
> comportamiento es idéntico al de antes.

### `GET /jobs/{jobId}/dashboard`
Read-model agregado para panel: incluye `metricas` (ver §6), `tasas`, `ultimoBloque`, progreso, etc.

### `GET /jobs/{jobId}/indicadores`
Umbrales del semáforo + **snapshot reciente** de la telemetría de vuelos y almacenes.
```json
{ "jobId":"...", "umbrales": { "verdeHasta":0.70, "ambarHasta":0.90 },
  "vuelos": [ /* filas CargaVuelo + bloqueIdx/horaInicio/horaFin */ ],
  "almacenes": [ /* filas OcupacionAlmacen + bloqueIdx/horaInicio/horaFin */ ] }
```
> **Snapshot acotado (anti-OOM):** `vuelos` y `almacenes` traen solo los **bloques MÁS recientes** de
> la ventana en RAM (el "ahora" del semáforo), acotados a `planificador.consulta.max-filas-pagina`
> (5000 por defecto) filas por sección. **No es un volcado histórico.** Para el histórico completo usa
> los endpoints **paginados** `/vuelos/carga` y `/almacenes/ocupacion`.

### `GET /jobs/{jobId}/vuelos/carga?desde=0&limit=`
**Respuesta PAGINADA (anti-OOM).** Cada fila es la carga **acumulada** del vuelo-día al cierre de su
bloque (ver §`CargaVuelo`); no sumar filas entre bloques.
```json
{ "jobId":"...", "desde":0, "proximoDesde":12, "hayMas":true,
  "bloquesPublicados":43, "terminado":false, "total":N,
  "vuelos": [ CargaVuelo + bloqueIdx, horaInicio, horaFin ] }
```
> **Paginación (importante).** El front empieza en `desde=0` y, **mientras `hayMas` sea `true`**, vuelve
> a pedir con `desde=proximoDesde`; para **refrescar**, reinicia en `desde=0`. `total` = filas **en esta
> página** (no el total global). `limit` = filas por página, **clampeado** al tope del servidor
> (`planificador.consulta.max-filas-pagina`, 5000); `limit<=0` usa ese default. Una primera llamada sin
> paginar ya viene **acotada** (no vuelca todo).
> - **Cursor OPACO.** `desde`/`proximoDesde` son un cursor de reanudación: en la ventana RAM cuentan
>   bloques, en el histórico BD cuentan filas. **No lo interpretes**, solo reúsalo. Es válido **dentro de
>   un mismo recorrido** (de `desde=0` hasta `hayMas=false`).
> - **Histórico desde BD (anti-OOM):** en corridas largas los bloques viejos se purgan de RAM (buffer
>   deslizante). Cuando el histórico cae fuera de esa ventana y el job persistió su solución, las páginas
>   se **reconstruyen desde BD**: ahí `bloqueIdx` es un **orden temporal** (no el bloque de cálculo) y
>   `horaInicio`/`horaFin` van `null`. Mientras todo cabe en la ventana, las filas llevan su bloque real.

### `GET /jobs/{jobId}/almacenes/ocupacion?desde=0&limit=`
**Respuesta PAGINADA (anti-OOM)**, mismo contrato de cursor que `/vuelos/carga`. La ocupación es el
**pico concurrente acumulado por (aeropuerto, día)** al cierre de su bloque (ver §`OcupacionAlmacen`);
no sumar filas entre bloques.
```json
{ "jobId":"...", "desde":0, "proximoDesde":12, "hayMas":false,
  "bloquesPublicados":43, "terminado":false, "total":N,
  "almacenes": [ OcupacionAlmacen + bloqueIdx, horaInicio, horaFin ] }
```
> **Paginación + ventana reciente (anti-OOM):** el front pagina igual que `/vuelos/carga` (`desde=0` →
> mientras `hayMas`, `desde=proximoDesde`). A diferencia de `/vuelos/carga`, este agregado **NO** se
> reconstruye desde BD (la ocupación concurrente por slot no es derivable directamente): solo cubre la
> **ventana reciente** de bloques en RAM. Para el histórico completo, **acumula incrementalmente** (como
> `/bloques`) o usa `/almacenes/serie?desde=N`.

### `GET /jobs/{jobId}/almacenes/serie?desde=N` — **ocupación EN VIVO (serie por hora)**
Serie temporal de ocupación por **slot de 60 min en eje UTC** — la granularidad nativa del
modelo interno — para actualizar en vivo las maletas de cada almacén mientras el reloj de
animación recorre el bloque. Una serie por bloque publicado; misma paginación que `/bloques`
(`desde` = índice de bloque; patrón: `desde = total` recibido).
```json
{ "jobId":"...", "desde":0, "total":43, "primeraSerieDisponible":8, "terminado":false,
  "series": [ { "bloqueIdx":0, "slots": [
      { "aeropuerto":"SEQM", "hora":"2026-01-02T13:00", "capacidadMaxima":430,
        "ocupacion":117, "porcentajeOcupacion":27.2, "semaforo":"VERDE" } ] } ] }
```
> **`desde` purgado ⇒ `series: []`** — misma semántica y mismo patrón de resync que `/bloques`
> (`primeraSerieDisponible` = primer índice retenido; si `desde` cae antes, la respuesta va vacía
> en vez de realinear y mal-etiquetar `bloqueIdx`).
- `hora` = inicio del slot (UTC); el slot cubre `[hora, hora+60min)`. Mientras el reloj de
  animación esté dentro del slot, mostrar esa `ocupacion`.
- `ocupacion` = maletas presentes **a la vez** en ese slot, ACUMULADO vigente (estadías de
  todos los bloques hasta este, incluida la espera en origen de envíos sin ruta del backlog).
  Es exactamente lo que valida el motor — no derivar ni sumar nada.
- Cada serie lista solo los slots **tocados por su bloque**; entre bloques, la fila más
  reciente de un mismo (aeropuerto, hora) gana (igual que `CargaVuelo`).
- Para la carga de un avión NO hace falta serie: la carga de un vuelo-día es constante durante
  el vuelo y **definitiva una vez despegado** (el motor nunca asigna a vuelos pasados) — usar
  la fila más reciente de `cargasVuelos` + `salidaUtc/llegadaUtc` de los tramos.

### `GET /jobs/{jobId}/vuelos/usados?desde=0`
`VuelosUsadosResponse`: lista incremental de vuelos efectivamente usados (para animación de la red).
```json
{ "jobId","desde":0,"bloquesPublicados":43,"terminado":false,"total":N,
  "vuelos": [ { "flightKey":"1501|2026-01-03T19:30", "bloqueIdx":2,
                "vueloId":"1501", "origen":"SKBO", "destino":"SEQM",
                "fechaSalida":"2026-01-03T19:30", "fechaLlegada":"2026-01-03T20:17",
                "cantidadMaletas":145, "cantidadEnvios":2, "envioIds":["B1","B2"] } ] }
```
> **Eje UTC:** `fechaSalida`, `fechaLlegada` y la `flightKey` (`vueloId|fechaSalida`) están en
> **UTC** — el mismo eje que `TramoRuta.salidaUtc` y `CargaVuelo.fechaSalida`, así las tres
> fuentes del mismo vuelo-día casan entre sí. (Cambio respecto a versiones anteriores, que
> usaban la hora local de cada extremo y mezclaban husos.)
>
> **Histórico desde BD (anti-OOM):** igual que `/vuelos/carga` — cuando el histórico cae fuera de la
> ventana reciente y el job persistió, se reconstruye completo desde BD; en esas filas `bloqueIdx` es
> un **orden temporal** (no el bloque de cálculo) y `envioIds` va **vacío** (`[]`). En la ventana
> reciente las filas llevan su bloque real y sus `envioIds`.

### `GET /jobs/{jobId}/asignaciones?desde=N&aeropuerto=&vueloId=&soloEnrutadas=false`
Asignaciones por envío con filtros opcionales. **Stream INCREMENTAL** (mismo patrón que `/bloques`).
```json
{ "jobId","desde":0,"aeropuerto":null,"vueloId":null,"soloEnrutadas":false,"total":N,
  "primerBloqueDisponible":8,
  "asignaciones": [ { "bloqueIdx","horaInicio","horaFin","asignacion": AsignacionMaleta } ] }
```
> **Consumo INCREMENTAL (importante).** `desde` es un **índice de bloque**: devuelve las asignaciones
> de los bloques con índice `>= desde`. El front es **dueño de su propio histórico**: guarda lo que
> recibe y avanza el cursor `desde = total_bloques_consumidos` (el mismo `total`/`bloquesPublicados`
> que ya usa con `/bloques`), sumando solo el **delta nuevo**. Cada asignación se entrega **una sola
> vez** (cuando su bloque es nuevo); el backend **no** es el archivo histórico completo de asignaciones.
> - **No** volver a pedir `desde=0` en cada poll (eso ya no devuelve todo el histórico).
> - **Ventana de retención:** las asignaciones **y los bloques** se conservan en RAM solo para los
>   **últimos N bloques** (configurable, `planificador.scenario.max-bloques-buffer`); los más viejos se
>   **purgan** (para sostener corridas del dataset completo sin agotar memoria). Pedir un `desde` ya
>   purgado —p. ej. `desde=0` tras recargar en una corrida larga— devuelve `asignaciones` **vacía**
>   (no un error), con `primerBloqueDisponible` = primer bloque aún retenido: misma semántica y
>   mismo patrón de resync que `/bloques` (reengancharse vía `GET /jobs/activo` y retomar en la punta).
> - Los **agregados derivados** `/vuelos/usados`, `/vuelos/carga` y `/almacenes/ocupacion` son
>   **paginados**: `/vuelos/usados` por `desde` (tail incremental); `/vuelos/carga` y
>   `/almacenes/ocupacion` por `desde`/`limit` (recorrer con `proximoDesde` hasta `hayMas=false`).
>   `/vuelos/usados` y `/vuelos/carga` se **reconstruyen desde BD** cuando el histórico cae fuera de la
>   ventana (ahí `bloqueIdx` = orden temporal; `envioIds` vacío en `/vuelos/usados`);
>   `/almacenes/ocupacion` queda a **ventana reciente** (acumular incrementalmente o usar `/almacenes/serie`).

### `GET /jobs/{jobId}/envios/{idEnvio}?en=<instanteUtc>` — estado de UN envío
Estado "en ruta" de un único envío por su `idEnvio`. **Pensado para cuando el envío ya NO está en
pantalla** (pertenece a un bloque anterior, purgado de la ventana reciente de bloques en RAM
—`max-bloques-buffer`, 35 por defecto—): el
detalle se reconstruye desde la **solución persistida en BD** y se le añade el estado actual.
Devuelve un `EnvioEstadoResponse`: la `asignacion` (mismo esquema que el campo `asignacion` de
`/asignaciones` y `/bloques`) **con cada `tramos[].estado` ya clasificado**, más el estado global.
```json
{ "asignacion": {
    "batchId":"SKBO-12345", "origen":"SKBO", "destino":"SEQM", "cantidad":3,
    "enrutada":true, "cumpleSLA":true,
    "rutaVuelos":["SKBO-SPIM-08:30","SPIM-SEQM-12:10"],
    "registroUtc":"2026-01-03T13:05", "registroLocal":"2026-01-03T08:05",
    "tramos":[ { /* TramoRuta */ "estado":"COMPLETADO" }, { /* ... */ "estado":"PENDIENTE" } ] },
  "estado":"EN_ESCALA",
  "instanteReferencia":"2026-01-03T15:00", "instanteDerivadoDelJob":true,
  "ubicacionActual":"SPIM", "tramoActualIdx":null,
  "tramosCompletados":1, "tramosTotales":2, "llegadaFinalUtc":"2026-01-03T19:20" }
```
> **Parámetro `en` (opcional).** Instante UTC de referencia (ISO sin offset, p. ej.
> `2026-01-03T15:00`). El front lo pasa con el reloj de su animación (sirve también para *rebobinar*).
> Si se **omite**, el backend usa el `horaFin` del **último bloque publicado** del job (el "ahora" de
> la simulación) y marca `instanteDerivadoDelJob:true`.
>
> **Estado del envío** (`estado`): `PROGRAMADO` (aún no sale su 1.er vuelo, está en `origen`),
> `EN_VUELO` (en un tramo en curso; ver `tramoActualIdx`, `ubicacionActual:null`), `EN_ESCALA`
> (esperando conexión en `ubicacionActual`), `ENTREGADO` (ya en el destino), `DESCONOCIDO` (no se
> pudo fijar el "ahora": job sin bloques y sin `en`).
> **Estado de cada tramo** (`tramos[].estado`): `COMPLETADO` (`llegadaUtc <= ahora`), `EN_CURSO`
> (`salidaUtc <= ahora < llegadaUtc`), `PENDIENTE` (`salidaUtc > ahora`).
>
> **Flujo recomendado.** El front busca el envío primero en su histórico local (los bloques que ya
> recibió por `/bloques` o `/asignaciones`); **solo si no lo tiene**, llama a este endpoint.
> - **`200`** con el `EnvioEstadoResponse`.
> - **`400`** si `en` tiene formato inválido.
> - **`404`** si el job no existe, **o** si el envío no tiene ruta activa en este job: porque no
>   existe, porque quedó **en backlog / sin ruta** (esos no se persisten), o porque la BD ya refleja
>   **otra corrida**.
> - **Solo refleja la corrida persistida.** El backend persiste **una corrida a la vez** y limpia las
>   tablas al iniciar cada nueva (`TRUNCATE`). Este endpoint responde el detalle del **último job que
>   tomó la persistencia** (durante y después de su ejecución, hasta que otro job arranque). En
>   ejecuciones sin persistencia (p. ej. perfil `smoke`, o un 2.º job concurrente) devolverá `404`.
> - **Tras una cancelación de vuelo** (Fase 2), el endpoint devuelve la **ruta activa nueva**: el motor
>   **conserva los tramos ya volados** y re-enruta el resto **desde la posición física** del envío (la
>   escala donde está), sin volver al origen. La ruta nueva puede tener más escalas y llegar más tarde,
>   pero respeta el prefijo volado.
> - **Envío VARADO** (caso nuevo de Fase 2): si no hay ruta desde la escala, el envío espera ahí y
>   reintenta en bloques siguientes. Se reporta con `enrutada:false` PERO con `tramos[]` no vacíos (los
>   tramos ya volados, todos `COMPLETADO`) y `estado:"EN_ESCALA"` en `ubicacionActual`. **Trata
>   `enrutada` como "llegó al destino", NO como "tiene tramos"** (un varado tiene tramos pero aún no
>   llegó). En un poll posterior, si consigue ruta, aparecerá con más tramos y `ENTREGADO`.
> - **404 transitorio:** si la consulta cae justo cuando el job reescribe esa ruta (re-enrutamiento en
>   curso), puede devolver `404` un instante; **reintentar** lo resuelve.

### `GET /demanda/resumen?desde=&hasta=&top=20`
Demanda agregada del dataset (no requiere job). `porOrigen`, `porDestino`, `porOD` (top N),
más `totalEnvios` y `totalMaletas`. `desde`/`hasta` en **UTC**.
> **Rango acotado (anti-OOM):** la agregación se hace **en BD** (no carga los envíos en RAM). El rango
> se **acota** a un span máximo (`planificador.consulta.demanda-max-dias`, **31** por defecto): si falta
> `hasta` o el span lo supera, el backend recorta `hasta = desde + demanda-max-dias` y **reporta el rango
> efectivo** en los campos `desde`/`hasta` de la respuesta. Pedir sin parámetros ya **no** escanea el
> dataset entero; conviene enviar siempre un rango acotado.

### `POST /jobs/{jobId}/cancelar`
`{ "jobId", "cancelado": true|false }`. Detiene el job (orden del front).

### `POST /jobs/{jobId}/reiniciar` — botón "reinicio"
Detiene la simulación en curso y lanza una **nueva con los MISMOS parámetros** de la ejecución
anterior (misma seed ⇒ re-juego idéntico; mismo escenario, algoritmo y fechaInicio; y los overrides
de E2 / el umbral de E3). Crea un **jobId NUEVO**: el front debe reengancharse a ese id (reiniciar
su polling y animación). Funciona en E1/E2/E3, tanto si el job estaba activo como si ya terminó.
```json
{ "jobIdAnterior": "abc-123", "jobId": "xyz-789", "escenario": "1",
  "algoritmo": "alns", "seed": 42, "estado": "encolado" }
```
- `202` con el cuerpo de arriba · `404` si el `jobId` no existe · `400` si el escenario no es reiniciable.
- El executor es single-thread: el job nuevo se encola y arranca cuando el anterior se detiene
  (sin solapamiento). El job anterior queda en estado `cancelado`.

### `POST /jobs/{jobId}/cancelar-vuelo` — cancelar un vuelo **en vivo**
Cancela un vuelo concreto **solo el día indicado** mientras el job corre (E1 async / E2 / E3). El
vuelo queda no disponible y los envíos que lo usaban se **re-enrutan desde su posición física** en el
instante de la simulación (Fase 2): conservan los tramos ya volados y buscan ruta nueva **desde la
escala** donde están (o quedan **varados** ahí y reintentan); **nunca "reaparecen" en el origen**. Un
envío que ya había volado todos sus tramos cuando se procesa la cancelación conserva su ruta. El vuelo
se identifica con los mismos datos de `/jobs/{jobId}/vuelos/usados`. **Body JSON:**
```json
{ "origen": "SKBO", "destino": "SEQM", "fechaHoraSalida": "2026-01-03T14:30" }
```
> **`fechaHoraSalida` va en UTC.** Es el mismo `fechaSalida` que devuelve `/jobs/{jobId}/vuelos/usados`
> (eje UTC, igual que `TramoRuta.salidaUtc`): **reenviarlo tal cual, sin convertir a hora local**.
> El backend lo compara contra el vuelo-día normalizado a UTC; enviar la hora de pared local
> cancelaría el vuelo equivocado o no encontraría ninguno (salvo aeropuertos con offset 0).
- `202` si se encoló: `{ "jobId", "encolado": true, "origen", "destino", "fechaHoraSalida" }`.
- `404` si el `jobId` no existe · `409` si el job ya terminó (no activo).
- La orden se aplica al inicio del siguiente bloque. Una vez aplicada, aparece en
  `vuelosCancelados` de `GET /jobs/{jobId}/estado` (con `enviosAfectados`) y, al final,
  en el CSV `*-vuelos-cancelados.csv` del ZIP de auditoría (ver §8). Si no casó ningún vuelo-día,
  aparece en `cancelacionesNoAplicadas` de `GET /jobs/{jobId}/estado`.

### `POST /jobs/{jobId}/agregar-vuelo` — agregar un vuelo **en caliente** (efímero)
Agrega un vuelo nuevo mientras el job corre (E1 async / E2 / E3). El vuelo es **recurrente diario**
(se repite cada día de la simulación, como todo el dataset) y **efímero**: vale **solo para esa
corrida** y se revierte al iniciar la corrida siguiente, sin tocar el dataset maestro. Las rutas que
lo usen se recalculan a partir del bloque siguiente. **Body JSON:**
```json
{ "origen": "SKBO", "destino": "SEQM", "horaSalida": "08:30", "horaLlegada": "10:15", "capacidad": 300 }
```
> `horaSalida`/`horaLlegada` van en **hora LOCAL** ("HH:mm"): `horaSalida` en la del origen y
> `horaLlegada` en la del destino — el mismo criterio del dataset. El backend normaliza a UTC.
> Ambos aeropuertos deben existir (o venir de un `agregar-aeropuerto` encolado en el mismo job).
- `202` si se encoló: `{ "jobId", "encolado": true, "idVuelo": "SKBO-SEQM-0830", "origen", "destino" }`.
- `400` si algún campo es inválido (ICAO desconocido, horas mal formadas, `capacidad < 1`, id ya existente).
- `404` si el `jobId` no existe · `409` si el job ya terminó (no activo).
- Se aplica al inicio del siguiente bloque. Una vez aplicado aparece en `vuelosAgregados` de
  `GET /jobs/{jobId}/estado`; si se rechaza al aplicarlo, en `altasVueloNoAplicadas` (con `motivo`).
  El vuelo aparece también en `GET /vuelos` y en `vuelosPlaneados` del resultado de esa corrida.

### `POST /jobs/{jobId}/agregar-aeropuerto` — agregar un aeropuerto **en caliente** (efímero)
Agrega un aeropuerto nuevo mientras el job corre. También **efímero** (se revierte al iniciar la
corrida siguiente). Por sí solo no cambia ninguna ruta: participa cuando se agregan vuelos en caliente
hacia/desde él (`agregar-vuelo`) o se le inyectan envíos. **Body JSON:**
```json
{ "icao": "SPQU", "ciudad": "Arequipa", "husoHorario": -5, "capacidad": 400,
  "latitud": -16.34, "longitud": -71.58, "continente": "AM" }
```
> `icao` = 4 letras mayúsculas, no existente. `husoHorario` = GMT offset entero [-12..14] (obligatorio).
> `continente` (AM/EU/AS) fija el SLA; si se omite se deriva del prefijo ICAO (S→AM, E/L/U→EU, O/V→AS)
> y, si el prefijo no lo permite, el alta se rechaza con `400` (envíe `continente` explícito).
- `202` si se encoló: `{ "jobId", "encolado": true, "icao" }`.
- `400` inválido · `404` job inexistente · `409` job no activo.
- Aparece en `aeropuertosAgregados` de `GET /jobs/{jobId}/estado` (o `altasAeropuertoNoAplicadas` con
  `motivo`), y en `GET /aeropuertos` de esa corrida.

> **Efímeras y anti-contaminación.** Los vuelos/aeropuertos agregados en caliente existen como filas
> reales (`efimero=TRUE`) para satisfacer las claves foráneas de la persistencia, pero se **eliminan**
> de BD + memoria al iniciar la corrida siguiente. Nunca contaminan el dataset maestro ni corridas
> futuras. Se pueden **cancelar** con `cancelar-vuelo` como cualquier otro vuelo dentro de la misma
> corrida.

---

## 6. Esquemas de datos

### `SimulacionResponse` (resultado final)
```
{ metricas: Metricas, totalBloques: int, vuelosPlaneados: VueloBackend[],
  aeropuertosInfo: { [cod]: AeropuertoDTO }, k: int, saMinutos: int }
```

### `Metricas`
```
procesadas, enrutadas, sinRuta, cumpleSLA, tardadas    : int   // en ENVÍOS
maletasIndividuales                                     : long  // maletas físicas
vuelosCancelados                                        : int   // vuelo-días cancelados por orden del usuario (en vivo)
tiempoEjecucionMs, taMinMs, taMaxMs, taPromedioMs, tiempoTotalAlgMs : long
advertenciaCalibracion                                  : bool  // Ta > 0.9·Sa
collapsoDetectado                                       : bool  // hubo colapso (E3 / almacén lleno)
bloqueColapso                                           : int   // índice del bloque del colapso (-1 si no)
motivoColapso                                           : string? // "almacen_lleno" | "backlog_definitivo" (ausente si no hubo)
detalleColapso                                          : string? // qué/dónde colapsó (ausente si no hubo)
instanteColapsoUtc                                      : string? // instante UTC ISO-8601 del colapso (ausente si no hubo)
backlogActual, backlogPico, sinRutaDefinitivo           : int
```

### `BloqueSimulacion`
```
horaInicio, horaFin            : string (ISO sin offset) // ventana UTC del bloque [scStart, scEnd)
                                                          // CONTIGUA: horaFin[N] == horaInicio[N+1]
horaInicioUtc, horaFinUtc      : string (ISO sin offset) // alias UTC explícito (mismo valor que
                                                          // horaInicio/horaFin; nunca null)
maletasProcesadas, maletasEnrutadas         : int  (delta del bloque, en envíos)
maletasProcesadasAcum, maletasEnrutadasAcum, maletasEntregadasAcum : long (maletas físicas)
asignaciones        : AsignacionMaleta[]
cargasVuelos        : CargaVuelo[]
ocupacionAlmacenes  : OcupacionAlmacen[]
alertaAlmacen       : AlertaAlmacen   // alerta de almacén cerca de colapso, ESPECÍFICA de este bloque
bloqueIdx           : int     // 0-based
taMs                : long    // duración real del bloque
scMinutos           : int     // Sc = K·Sa
```
> `horaInicio`/`horaFin` son los **límites UTC** de la ventana del bloque (`[scStart, scEnd)`) y los
> bloques son **contiguos**: `horaFin` de un bloque coincide con `horaInicio` del siguiente, sin
> solapes ni huecos. Sirven directamente como eje de la línea de tiempo de la animación.
> `horaInicioUtc`/`horaFinUtc` son alias del mismo valor (compatibilidad). Para el detalle de cada
> envío, seguir usando `registroUtc`/`salidaUtc` de cada asignación/tramo.

> `maletasEntregadasAcum` cuenta las maletas cuyo **último arribo (UTC) ya ocurrió** según el
> reloj UTC de la simulación (el `registroUtc` más reciente procesado). Es monótona entre bloques
> y nunca incluye entregas futuras.

> **No sumar deltas entre bloques:** `maletasProcesadas`/`maletasEnrutadas` (delta, en envíos)
> incluyen también los reintentos del backlog — un envío sin ruta del bloque 3 que se enruta en
> el bloque 7 cuenta en el delta de AMBOS. Para totales usar siempre los `*Acum`, que
> deduplican por envío.

> **Un envío puede reaparecer en bloques posteriores** (volvió del backlog, se replanificó, o su
> vuelo fue cancelado): cada reaparición trae su estado más reciente (`enrutada`, `rutaVuelos`,
> `tramos` nuevos). La regla del front es **"la última gana"**: mantener un mapa por `batchId` y
> sobrescribir con la asignación del bloque más reciente; los bloques antiguos no se corrigen
> retroactivamente.

### `AsignacionMaleta`
```
batchId, origen, destino : string
cantidad                 : int   // maletas físicas del envío
enrutada, cumpleSLA      : bool
rutaVuelos               : string[]   // ICAOs/ids de la ruta
tramos                   : TramoRuta[]
registroLocal            : string (ISO sin offset) // nacimiento del envío, hora local del origen
registroUtc              : string (ISO sin offset) // mismo nacimiento en UTC real (offset del origen aplicado)
```
> `registro*` se devuelve **siempre** (esté o no enrutado el envío). Es el instante desde el
> que las maletas existen esperando en el aeropuerto de origen, antes de su primer vuelo.
> **`enrutada` = el envío llegó/llegará a su DESTINO final**, no "tiene tramos". Con Fase 2 un envío
> puede estar **varado** en una escala (`enrutada:false` con `tramos[]` de los vuelos ya volados): para
> "¿tiene algún tramo dibujable?" usa `tramos.length`, no `enrutada`.

### `TramoRuta`
```
vueloId, origen, destino  : string
salidaLocal, llegadaLocal : string (ISO sin offset) // hora de pared local de cada extremo
                                                     // (origen para salida, destino para llegada)
salidaUtc, llegadaUtc     : string (ISO sin offset) // UTC real (offset de cada aeropuerto aplicado)
duracionMin               : int                     // duración real del vuelo = llegadaUtc − salidaUtc
estado                    : string (opcional)       // COMPLETADO/EN_CURSO/PENDIENTE; SOLO en /envios/{id}
```
> El motor ya planifica en **UTC** (normaliza vuelos y registros con el offset de cada
> aeropuerto), así que `salidaUtc`/`llegadaUtc` son UTC real, y `duracionMin` es la duración real
> del vuelo en minutos. **Para velocidad/animación del avión, usar `duracionMin` (o `salidaUtc`→
> `llegadaUtc`); NUNCA restar los `*Local`**, que están en husos distintos y dan duraciones falsas
> (un `LBSF→LATI` restando local da −34 min; un `SKBO→OPKC`, +600 min). Los `*Local` son solo para
> mostrar la hora de pared de cada ciudad al usuario.

### `CargaVuelo`
```
vueloId, origen, destino, fechaSalida, fechaLlegada : string
capacidadMaxima, cargaAsignada : int   // en maletas; cargaAsignada = carga ACUMULADA total
                                       // del vuelo-día (todos los bloques hasta este)
porcentajeCarga : double (0..100)
semaforo : "VERDE" | "AMBAR" | "ROJO"
```
> Cada bloque lista solo los vuelos-día que **tocó** (recibieron o liberaron carga en ese
> bloque), pero `cargaAsignada` es el **acumulado global** del vuelo-día — NO el delta del
> bloque — y el `semaforo` se calcula sobre ese acumulado. El front **no debe sumar** filas de
> bloques distintos: para el estado vigente de un vuelo-día, usar su fila más reciente (el
> último bloque que lo tocó).

### `OcupacionAlmacen`
```
aeropuerto, fecha : string
capacidadMaxima, ocupacionAsignada : int  // en maletas; ocupacionAsignada = pico concurrente
                                          // ACUMULADO del día (todos los bloques hasta este)
porcentajeOcupacion : double (0..100)
semaforo : "VERDE" | "AMBAR" | "ROJO"
```
> Misma semántica acumulada que `CargaVuelo`: cada bloque lista solo los almacenes-día cuyos
> slots tocó, pero `ocupacionAsignada` es el **pico concurrente acumulado** (maletas presentes a
> la vez, incluyendo estadías commiteadas en bloques anteriores y la espera en origen de envíos
> sin ruta del backlog) y el `semaforo` se calcula sobre ese pico. No sumar filas de bloques
> distintos; el estado vigente de un almacén-día es su fila más reciente.

### `AlertaAlmacen` (`alertaAlmacen` de cada `BloqueSimulacion`)
```
nivel : "VERDE" | "AMBAR" | "ROJO"   // semáforo del peor almacén del bloque (umbrales 0.70/0.90)
almacenCritico : string (ICAO)       // almacén con mayor % de ocupación del bloque (null si ninguno)
capacidadMaxima, ocupacion : int     // del almacén crítico
porcentajeOcupacion : double (0..100)
bloqueIdx : int                      // = BloqueSimulacion.bloqueIdx
```
> Alerta de almacén cerca de su capacidad, **específica de cada bloque** (no un valor global). Viaja
> dentro del bloque (vía `GET /jobs/{id}/bloques?desde=N`) para que el front la muestre justo cuando
> anima ese `bloqueIdx`, aunque el backend ya esté procesando bloques futuros. Para el detalle por
> almacén, ver `ocupacionAlmacenes[]`; para la alerta global vigente (almacén + SLA), `/alerta-colapso`.

### `AeropuertoDTO`
```
codigo : string, latitud, longitud : double
capacidadAlmacen : int (maletas, ACTUAL) , capacidadAlmacenOriginal : int (valor de fábrica)
gmt : number (offset horario)
```

### `VueloBackend` (`vuelosPlaneados` del resultado final y respuesta de `GET /vuelos`)
```
id : string                        // id de BD del vuelo — el MISMO que TramoRuta.vueloId
origen, destino : string (ICAO)
fechaSalida, fechaLlegada : string (ISO sin offset) // hora LOCAL de cada aeropuerto
capacidadMaxima : int (ACTUAL), capacidadMaximaOriginal : int (valor del TXT)
cargaAsignada : int (siempre 0 aquí)
```
> Es la **malla estática** de la red (TODOS los vuelos del dataset, desplazados al día de la
> simulación): sirve para pintar las rutas posibles del mapa. Sus horas están en la **hora local
> de cada aeropuerto** (como el dataset), NO en UTC — para animar movimiento usar siempre los
> tramos UTC de las asignaciones o `vuelos/usados`. `id` casa con `TramoRuta.vueloId` y con el
> `vueloId` de `CargaVuelo`/`VueloUsado`.

---

## 7. Alerta de colapso logístico inminente (`AlertaColapso`)

Anticipa los dos criterios de colapso reales (almacén lleno / SLA vencido) **antes** de que ocurran.
Solo informa (no detiene). Disponible en `GET /jobs/{id}/alerta-colapso` y dentro de `/estado`.

```json
{
  "nivel": "VERDE" | "AMBAR" | "ROJO",
  "mensaje": "almacén SEQM al 92% de capacidad | envío E123 al 8% de su SLA en backlog",
  "bloque": 142,
  "utilAlmacenMax": 0.92,        // 0..1+ (pico de utilización de almacén del bloque)
  "almacenCritico": "SEQM",      // aeropuerto con la utilización pico
  "holguraSlaMin": 0.08,         // fracción de SLA restante del envío más urgente (1.0 si no hay backlog)
  "envioUrgente": "E123",
  "causaDominante": "almacen"    // "almacen" | "sla" | "ambos" | ausente (VERDE): qué señal levantó el nivel
}
```
- **VERDE** sin riesgo · **AMBAR** acercándose · **ROJO** a punto de colapsar.
- `causaDominante` indica el **cómo** sin parsear `mensaje`: `almacen` (almacén cerca de capacidad),
  `sla` (backlog por vencer), `ambos` (las dos), o **se omite** cuando el nivel es VERDE.
- Umbrales configurables (`planificador.alerta-colapso.*`): almacén ámbar 0.85 / rojo 0.95;
  holgura SLA ámbar 0.25 / rojo 0.10.

> **Colapso real:** cuando ocurre, la simulación se **detiene** (E1, E2 y E3) y el resultado final
> (`metricas`) trae el **dónde / cómo / cuándo** del colapso:
> - `collapsoDetectado` (bool) y `bloqueColapso` (índice del bloque, -1 si no).
> - `motivoColapso`: `"almacen_lleno"` (almacén a capacidad) o `"backlog_definitivo"` (SLA vencido).
> - `detalleColapso`: texto legible del envío/almacén que colapsó.
> - `instanteColapsoUtc`: instante **UTC** ISO-8601 (fin de la ventana del bloque del colapso).
>
> Los tres últimos se **omiten** si no hubo colapso. El servidor también lo registra en consola.

---

## 8. Descargas

### `GET /jobs/{jobId}/auditoria.zip?desde=&hasta=`
Auditoría como **ZIP de varios CSV** (≤ 50 000 filas por archivo). Header `X-Audit-Rows`.

> 🔴 **Cambio — la auditoría se genera SOLO al pedirla (on-demand).** Antes el backend la generaba
> automáticamente al terminar cada job; ahora **no** (evita producir auditorías que nadie quiere y,
> sobre todo, que su escritura desde BD —cientos de MB en corridas grandes— bloquee el motor). El ZIP
> se construye en el momento de esta petición leyendo la solución de BD en streaming, así que **puede
> tardar**: el front debe mostrar una **pantalla de carga** mientras dura la descarga.
>
> **Filtro por fecha (opcional):** `desde`/`hasta` son instantes **UTC** ISO-8601 (p. ej.
> `2027-11-01T00:00`) sobre el `readyTime` del envío — `desde` inclusivo, `hasta` exclusivo. **Sin
> parámetros = auditoría COMPLETA.** Acotar por rango produce un ZIP más pequeño y rápido.
>
> **Verificación contra la simulación:** el rango se valida contra la **ventana realmente simulada**
> del job. Si `desde ≥ hasta` o el rango **no se solapa** con esa ventana → `400` con la ventana válida
> en el mensaje. Si se solapa **parcialmente**, el backend **recorta** el rango a la ventana y devuelve
> el header **`X-Audit-Range: <desdeEfectivo>/<hastaEfectivo>`** (UTC) con lo realmente exportado.
>
> **Códigos:** `200` con el ZIP · `400` rango inválido/fuera de la simulación · `404` si el job no
> existe · `409` si el job **aún está activo** (la auditoría estará disponible al terminar) **o** si su
> solución ya fue **reemplazada por una corrida posterior** (el backend persiste una corrida a la vez;
> lanzar otro escenario hace `TRUNCATE`). El `409` trae `{ "error": "..." }`. (Ya **no** existe el `204`.)
>
> Headers de respuesta: `X-Audit-Rows` (total de filas de envíos) y, si hubo recorte, `X-Audit-Range`.

Contenido del ZIP:
- `<jobId>-<inicio>-<fin>.csv` — un archivo por tramo de hasta 50 000 envíos, con **25 columnas** por
  envío en este orden: `idEnvio, origen, destino, clienteId, cantidad, tipoEnvio, registroHHMM,
  deadlineMin, exitoso, motivoFalla, ruta, numTramos, numEscalas, tiempoVueloMin, tiempoEsperaMin,
  tiempoTotalMin, llegadaMin, slackSlaMin, slackSlaHoras, cumpleSLA, sinCiclos, escalaMinOK,
  scoreCalidad, fechaHoraInicio, fechaHoraFin`. Las booleanas (`exitoso, cumpleSLA, sinCiclos,
  escalaMinOK`) permiten la validación formal por envío; `tipoEnvio` es `INTRACONTINENTAL`/
  `INTERCONTINENTAL`. **Las columnas de tiempo (`registroHHMM`, `fechaHoraInicio`,
  `fechaHoraFin`) están en UTC** (el `readyTime` del envío y la llegada ya normalizados); por eso un
  envío registrado el 2026-01-02 en hora local de un aeropuerto GMT+ puede figurar con fecha del día
  anterior. Comparten el mismo eje UTC que `primeraVentana` (§3) y que `horaInicio`/`horaFin` de los
  bloques.
- `<jobId>-vuelos-cancelados.csv` — **siempre presente**; un registro por vuelo cancelado en vivo
  durante la corrida. Columnas: `origen,destino,fechaHoraSalida,enviosAfectados`. Si no hubo
  cancelaciones, lleva solo la cabecera. **Cuando se pide por rango/día, trae solo las cancelaciones
  de ese período** (filtradas por su fecha de cancelación).

### `GET /jobs/{jobId}/auditoria/dia?fecha=YYYY-MM-DD`
Atajo cómodo: auditoría de **un día** concreto. `fecha` se interpreta en **UTC** y se traduce a
`desde = fecha T00:00`, `hasta = fecha+1 T00:00`. Misma respuesta y códigos que `auditoria.zip`
(incluida la verificación contra la ventana simulada y el header `X-Audit-Range` si se recorta); el
CSV de cancelaciones trae solo las de ese día.

### `GET /jobs/{jobId}/auditoria/estimacion?desde=&hasta=`
Estima **sin generar el ZIP** cuántos archivos tendría la auditoría (para avisar del tamaño antes de
descargar). `desde`/`hasta` opcionales, mismo eje **UTC** y misma verificación de rango que
`auditoria.zip`. Solo hace conteos en BD (barato).

```json
{
  "filasEnvios": 152340,          // envíos a exportar en el rango (enrutados + sin ruta)
  "csvEnvios": 4,                 // archivos de envíos = ceil(filasEnvios / filasPorArchivo)
  "filasCancelaciones": 12,       // vuelo-días cancelados en el rango
  "csvCancelaciones": 1,          // el CSV de cancelaciones siempre se emite (aun vacío)
  "totalCsv": 5,                  // csvEnvios + csvCancelaciones
  "filasPorArchivo": 50000,
  "desdeEfectivo": "2027-11-01T00:00",  // rango realmente contado (recortado a la ventana simulada); ausente si null
  "hastaEfectivo": "2027-11-02T00:00",
  "recortado": false              // true si se ajustó un límite explícito a la ventana
}
```
**Códigos:** `200` con la estimación · `400` rango inválido/fuera de la simulación · `404` job
inexistente · `409` job aún activo o solución reemplazada (`{ "error": "..." }`).

---

## 9. Carga de dataset (ingesta)

Reemplaza **todo** el dataset (aeropuertos + vuelos + envíos) de la BD por uno nuevo, de forma
**asíncrona** (los envíos pueden ser millones de líneas) y **destructiva**. Solo una ingesta a la
vez, y **no** puede coexistir con una simulación (ambos lados se rechazan con `409`).

### `POST /dataset/cargar` — subir un dataset (`multipart/form-data`)
Campos del formulario:

| Campo | Cardinalidad | Contenido |
|---|---|---|
| `aeropuertos` | 1 archivo | TXT de aeropuertos (formato abajo) |
| `vuelos` | 1 archivo | TXT de vuelos |
| `envios` | **N archivos** (repetir el campo `envios`) | un TXT por aeropuerto de origen, nombrado `_envios_<ICAO>_.txt` |

```bash
curl -X POST http://localhost:8080/api/planificador/dataset/cargar \
  -F "aeropuertos=@aeropuertos.txt" \
  -F "vuelos=@planes_vuelo.txt" \
  -F "envios=@_envios_SKBO_.txt" \
  -F "envios=@_envios_SEQM_.txt"
```
Respuestas:
- **202** + `IngestaEstado` inicial → encolada; hacer polling de su estado.
- **409** → hay una simulación activa, o ya hay una ingesta en curso. Cuerpo `{ "error": "..." }`.
- **400** → falta algún archivo, o un `envios` no permite derivar el ICAO de su nombre (debe ser
  `_envios_<ICAO>_.txt`). Cuerpo `{ "error": "..." }`.

> ⚠ **Destructivo y no transaccional.** Al iniciar hace `TRUNCATE` de `aeropuerto`/`vuelo`/`envio`
> (y en cascada `ruta_asignada`/`tramo_ruta`/`cancelacion_vuelo` — las soluciones previas se pierden).
> Si la carga falla a mitad, el dataset queda **parcial** (`fase="error"`) y hay que **re-subir**.
> Mientras la ingesta corre, lanzar cualquier escenario devuelve **409**.

### `GET /dataset/cargar/estado` — polling
`IngestaEstado`. **204** si nunca se ha iniciado una ingesta.
```json
{ "fase": "envios", "progreso": 0.62, "aeropuertos": 30, "vuelos": 2866,
  "enviosArchivosTotal": 30, "enviosArchivosProcesados": 18,
  "enviosInsertados": 5400000, "enviosDescartados": 1200,
  "error": null, "terminado": false,
  "inicio": "2026-06-16T10:00:00", "fin": null }
```
- `fase`: `encolada → limpiando → aeropuertos → vuelos → envios → recargando → completada` (o `error`).
- `progreso` [0..1] aproximado por fase. `terminado=true` al acabar (con éxito o error).
- `enviosDescartados`: líneas inválidas omitidas (RF03) sin abortar.
- Patrón: poll hasta `terminado=true`; `fase="completada"` = OK, `fase="error"` (+ `error`) = falló.
  Tras `completada` el backend recarga el dataset en memoria; las nuevas simulaciones ya lo usan.

### Formatos de archivo
UTF-8 (los de aeropuertos toleran BOM). Una entidad por línea.

**1. Aeropuertos** — campos separados por **2+ espacios**:
```
NN  ICAO  Ciudad  País  abrev  GMT  capacidad  Latitude: D° M' S'' N|S  Longitude: D° M' S'' E|W
01  SKBO  Bogota  Colombia  bogo  -5  430  Latitude: 4° 42' 5'' N  Longitude: 74° 8' 49'' W
```
- `ICAO` (4 letras) identifica el aeropuerto; el continente se deriva de su primera letra.
- `GMT` = huso horario (entero con signo); `capacidad` = del almacén, en maletas.
- Lat/Lon en grados-minutos-segundos; sufijo `S`/`W` ⇒ valor negativo.

**2. Vuelos** — `ORIGEN-DESTINO-HH:MM-HH:MM-capacidad` (se ignora la línea de cabecera `ORIG-DEST…`):
```
SKBO-SEQM-19:00-20:17-340
```
- Horas en **hora local** de cada extremo (salida local del origen, llegada local del destino).
- El `id_vuelo` interno se forma como `ORIGEN-DESTINO-HHMM` (sin `:`); los vuelos se repiten a diario.

**3. Envíos** — un archivo por aeropuerto de origen, nombrado **`_envios_<ICAO>_.txt`** (el ICAO de
origen se toma del **nombre del archivo**). Cada línea: `id-AAAAMMDD-HH-MM-ICAOdestino-maletas-cliente`:
```
1-20260102-08-30-SEQM-145-42
```
- `id` puede venir **vacío** (RF03: opcional); el id final del envío es `<ICAOorigen>-<id>`.
- `AAAAMMDD` = fecha; `HH`/`MM` = hora **local del origen** del registro (la BD la guarda como
  `fecha_hora_registro`; el motor la pasa a UTC restando el huso del origen).
- `maletas` y `cliente` son enteros. Las líneas con algún campo obligatorio faltante o mal formado se
  **descartan** (suman a `enviosDescartados`) sin abortar el archivo.

---

## 10. Notas para el front

- **Unidades:** la capacidad de vuelos y almacenes está en **maletas físicas**; las métricas de
  conteo de "procesadas/enrutadas/sinRuta" están en **envíos** (un envío agrupa varias maletas).
- **Semáforo:** verde ≤ 0.70, ámbar ≤ 0.90, rojo > 0.90 (umbrales en `/indicadores`).
- **Polling recomendado:** `estado` + `bloques?desde=` cada 2–5 s; `alerta-colapso` cada tick.
- **Eje de tiempo:** los aeropuertos están en husos distintos. Para cualquier cálculo de posición
  o cronología **global**, usar los campos `*Utc` (`registroUtc`, `salidaUtc`, `llegadaUtc`), que sí
  comparten reloj. Los `*Local` son solo para mostrar la hora de pared de cada ciudad.
- **Posicionar un envío en un instante `t` (UTC):**
  - `t < tramos[0].salidaUtc` → en el **aeropuerto de origen** (desde `registroUtc`).
  - `tramo.salidaUtc ≤ t < tramo.llegadaUtc` → **en vuelo**: interpolar lat/lon entre origen y
    destino del tramo (coordenadas en `GET /aeropuertos`) con `frac = (t − salidaUtc)/(llegadaUtc − salidaUtc)`.
  - entre `llegadaUtc` de un tramo y `salidaUtc` del siguiente → en el **aeropuerto de escala**.
  - `t ≥ último llegadaUtc` → **en destino**.
- **Supuestos del modelo de almacén** (los aplica el motor; la serie de `/almacenes/serie` ya
  los incluye): un envío entregado ocupa el almacén de **destino ~10 min** tras aterrizar
  (luego se retira); un envío **sin ruta** ocupa su almacén de **origen** desde `registroUtc`
  hasta que se enruta (reaparece en un bloque posterior) o vence su SLA (24 h
  intracontinental / 48 h intercontinental desde `registroUtc`).
```

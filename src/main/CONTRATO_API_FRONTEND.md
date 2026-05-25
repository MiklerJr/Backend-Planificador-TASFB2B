# Contrato de API para Frontend - Planificador TASF.B2B

Este documento describe las conexiones HTTP disponibles para el frontend: que entrega cada endpoint, como invocarlo y que estructura esperar en la respuesta.

## Base

- Base path: `/api/planificador`
- Base URL local sugerida: `http://localhost:8080/api/planificador`
- CORS permitido actualmente: `http://localhost:5173` y `http://localhost:3000`
- Autenticacion: no aplica actualmente.
- Fechas de entrada: formato ISO local, por ejemplo `2026-05-19T08:00:00`.
- Fechas de respuesta: JSON serializado por Jackson. En la practica el frontend debe tratarlas como strings ISO.

## Estados de jobs

Los endpoints asincronos devuelven o consultan un `jobId`.

Estados esperados:

| Estado | Significado para UI |
| --- | --- |
| `encolado` | Job creado, aun no empieza. |
| `calentando` | Preparando datos iniciales. |
| `ejecutando` | Simulacion en curso. |
| `completado` | Resultado final disponible. |
| `cancelado` | Cancelado por usuario o sistema. |
| `error` | Fallo durante ejecucion. Revisar campo `error`. |

Flujo recomendado:

1. Crear job con un endpoint `POST /escenario*/iniciar` (HTTP 202). Guardar `jobId`.
2. Consultar cada 1-3 s `GET /jobs/{jobId}/estado` — usar `progreso` y `progresoWarmup` para barras de avance.
3. En paralelo, pedir incrementales con `GET /jobs/{jobId}/bloques?desde={ultimoBloque+1}` y acumular en cliente.
4. Parar polling cuando `terminado=true` (en respuesta de `/bloques`) o cuando el `estado` del job entra en terminal (`completado`/`cancelado`/`error`).
5. Para dashboard, usar los endpoints agregados: `dashboard`, `indicadores`, `vuelos/carga`, `almacenes/ocupacion` y `asignaciones` — todos aceptan ser llamados durante la ejecucion y devuelven el ultimo estado conocido.
6. Al terminar (`estado="completado"`), pedir `GET /jobs/{jobId}/resultado` para obtener el `SimulacionResponse` final, y `muestra.csv` / `auditoria.csv` para exportes.

## Semaforos

Los endpoints de ocupacion y carga pueden devolver `semaforo`.

| Valor | Uso sugerido en UI |
| --- | --- |
| `VERDE` | Uso normal. |
| `AMBAR` | Uso alto, requiere atencion visual moderada. |
| `ROJO` | Saturacion o riesgo alto. |

Los porcentajes se devuelven como numeros de 0 a 100.

## Endpoints de configuracion

### GET `/dataset/info`

Entrega informacion general del dataset cargado.

Respuesta esperada:

```json
{
  "aeropuertos": 30,
  "vuelos": 1200,
  "demandas": 5000
}
```

Uso frontend:

- Mostrar estado inicial del backend.
- Validar que el dataset fue cargado antes de iniciar simulaciones.

### GET `/aeropuertos`

Entrega el catalogo estatico de aeropuertos como **mapa indexado por codigo IATA/OACI**. Responde con header `Cache-Control: public, max-age=3600` para que el front pueda cachearlo al iniciar.

Respuesta:

```json
{
  "SPIM": {
    "codigo": "SPIM",
    "latitud": -12.0219,
    "longitud": -77.1143,
    "capacidadAlmacen": 1000
  },
  "SKBO": {
    "codigo": "SKBO",
    "latitud": 4.7016,
    "longitud": -74.1469,
    "capacidadAlmacen": 850
  }
}
```

Uso frontend:

- Cachear en memoria/localStorage al cargar la app.
- Lookup O(1) por codigo en dibujo de bloques incrementales (`bloques?desde=N`) sin necesidad de joins.
- Poblar filtros, dibujar nodos del mapa y mostrar capacidad de almacen.

### GET `/escenarios`

Entrega catalogo dinamico de escenarios soportados con sus defaults (`Sa`, `Ta`, `K`, umbrales) tomados de `application.yaml`. El front no debe hardcodear estos valores.

Respuesta:

```json
{
  "saMinutos": 60,
  "taSegundos": 60,
  "motoresSoportados": ["alns", "aco"],
  "escenarios": [
    {
      "id": 1,
      "nombre": "Dia a dia (tiempo real)",
      "descripcion": "Planificacion viva: cada corrida cubre un unico bloque Sa. El wall-clock por bloque es Sa real, sin aceleracion.",
      "kDefault": 1,
      "simulaTiempoReal": true,
      "endpoints": {
        "iniciar":     "POST /api/planificador/escenario1/iniciar",
        "inicializar": "POST /api/planificador/escenario1/inicializar",
        "ventana":     "GET  /api/planificador/escenario1/ventana",
        "estado":      "GET  /api/planificador/escenario1/estado",
        "bloque":      "GET  /api/planificador/escenario1/bloque/{index}"
      }
    },
    {
      "id": 2,
      "nombre": "Periodo (3/5/7 dias)",
      "descripcion": "Replays/simulaciones de un periodo cerrado. Entre bloques duerme (Sa - Ta) cuando simularTiempoReal2=true.",
      "kDefault": 14,
      "simulaTiempoReal": true,
      "endpoints": { "iniciar": "POST /api/planificador/escenario2/iniciar" }
    },
    {
      "id": 3,
      "nombre": "Hasta colapso",
      "descripcion": "Estres / capacity planning. Avanza lo mas rapido posible hasta disparar la condicion de colapso.",
      "kDefault": 75,
      "simulaTiempoReal": false,
      "umbralColapso": 0.20,
      "umbralColapsoBacklog": 0.30,
      "endpoints": { "iniciar": "POST /api/planificador/escenario3/iniciar" }
    }
  ]
}
```

Uso frontend:

- Poblar selector de escenario y formularios con los `kDefault` y `umbralColapso*`.
- Validar contra `motoresSoportados` antes de enviar `algoritmo`.

### GET `/jobs?activos=true`

Lista los jobs en memoria del registry. Por defecto retorna **solo activos** (`encolado`, `calentando`, `ejecutando`). Util para reengancharse a una simulacion tras un refresh sin haber persistido el `jobId`.

Parametros:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `activos` | boolean | `true` | Si es `false`, retorna ademas los terminados (completado/cancelado/error) aun vivos en memoria. |

Respuesta:

```json
{
  "jobs": [
    {
      "jobId": "esc2-abc123",
      "escenario": "2",
      "algoritmo": "alns",
      "estado": "ejecutando",
      "k": 14,
      "seed": 42,
      "fechaInicio": "2026-05-19T08:00:00",
      "inicio": "2026-05-25T11:32:10",
      "fin": null,
      "progreso": 0.42,
      "progresoWarmup": 1.0
    }
  ],
  "total": 1
}
```

Uso frontend:

- Recuperar jobs activos tras un refresh.
- Mostrar historial simple de ejecuciones recientes.

## Iniciar simulaciones

### POST `/escenario1/iniciar`

Inicia escenario 1 de forma asincrona.

Query params:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `cancelProb` | number | `0.0` | Probabilidad de cancelacion de vuelo. |
| `algoritmo` | string | `alns` | Motor de planificacion. Valores esperados: `alns`, `aco`. |
| `seed` | number | opcional | Semilla para reproducibilidad. |

Respuesta (HTTP 202 Accepted):

```json
{
  "jobId": "esc1-abc123",
  "escenario": "1",
  "algoritmo": "alns",
  "k": 1,
  "seed": 42,
  "estado": "encolado"
}
```

### POST `/escenario1/inicializar`

Inicializa escenario 1 de forma **sincrona** sin lanzar job. Util para pantallas que requieren precargar la ventana de simulacion (catalogo de aeropuertos, primer bloque listo en memoria) antes de empezar a consumir.

Query params iguales a `/escenario1/iniciar`. La respuesta es el mapa de estado devuelto por `service.inicializarEscenario1(...)`.

### GET `/escenario1/ventana`

Entrega la ventana temporal configurada para escenario 1.

Uso frontend:

- Mostrar rango de simulacion antes de ejecutar.

### GET `/escenario1/estado`

Consulta estado del flujo legacy de escenario 1.

Nota: para pantallas nuevas se recomienda usar `GET /jobs/{jobId}/estado`.

### GET `/escenario1/bloque/{index}`

Entrega un bloque puntual del flujo legacy de escenario 1.

Nota: para pantallas nuevas se recomienda usar `GET /jobs/{jobId}/bloques?desde=`.

### POST `/escenario2/iniciar`

Inicia escenario 2 de forma asincrona.

Query params:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `k` | number | `14` | Horizonte/configuracion K del algoritmo. |
| `cancelProb` | number | `0.0` | Probabilidad de cancelacion. |
| `algoritmo` | string | `alns` | Valores esperados: `alns`, `aco`. |
| `seed` | number | opcional | Semilla para reproducibilidad. |
| `fechaInicio` | string | opcional | Inicio de simulacion en ISO local. |
| `sa` | number | opcional | Parametro SA en minutos. |
| `ta` | number | opcional | Parametro TA en minutos. |
| `dias` | number | opcional | Duracion del escenario en dias. |

> **Override por job:** `sa`, `ta` y `dias` permiten que cada job ejecute con su propia ventana sin tocar la configuracion global del yaml. Ejemplo: `/escenario2/iniciar?k=120&sa=5&dias=5&algoritmo=alns` → ventanas = (5·24·60)/5 = 1440 bloques de Sc=K·Sa.

Respuesta (HTTP 202 Accepted):

```json
{
  "jobId": "esc2-abc123",
  "escenario": "2",
  "algoritmo": "alns",
  "k": 14,
  "seed": 42,
  "estado": "encolado",
  "sa": 60,
  "ta": 60,
  "dias": 3,
  "fechaInicio": "2026-05-19T08:00:00"
}
```

Las claves `sa`, `ta`, `dias` y `fechaInicio` solo aparecen si fueron enviadas en el request.

### POST `/escenario3/iniciar`

Inicia escenario 3, orientado a colapso o estres.

Query params:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `k` | number | `75` | Horizonte/configuracion K del algoritmo. |
| `cancelProb` | number | `0.1` | Probabilidad de cancelacion. |
| `umbralColapso` | number | `0.20` | Umbral para declarar colapso. |
| `algoritmo` | string | `alns` | Valores esperados: `alns`, `aco`. |
| `seed` | number | opcional | Semilla para reproducibilidad. |

Respuesta (HTTP 202 Accepted):

```json
{
  "jobId": "esc3-abc123",
  "escenario": "3",
  "algoritmo": "alns",
  "k": 75,
  "seed": 42,
  "umbralColapso": 0.20,
  "estado": "encolado"
}
```

## Consultar jobs

### GET `/jobs/{jobId}/estado`

Entrega el estado actual de un job. Es el endpoint de polling principal.

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "escenario": "2",
  "algoritmo": "alns",
  "seed": 42,
  "fechaInicio": "2026-05-19T08:00:00",
  "k": 14,
  "estado": "ejecutando",
  "bloqueActual": 12,
  "totalBloques": 48,
  "progreso": 0.25,
  "bloqueWarmup": 0,
  "totalBloquesWarmup": 0,
  "progresoWarmup": 1.0,
  "posicionEnCola": 0,
  "canceladoPorUsuario": false,
  "taPromedioMs": 1820,
  "inicio": "2026-05-25T11:32:10",
  "fin": null,
  "error": null
}
```

Campos clave:

| Campo | Significado |
| --- | --- |
| `progreso` | Avance 0.0-1.0 sobre `totalBloques`. |
| `bloqueWarmup` / `totalBloquesWarmup` / `progresoWarmup` | Si `fechaInicio` obliga a simular hasta esa fecha antes de publicar, mientras `estado="calentando"` el front muestra esta barra. Cuando termina el warmup, `progresoWarmup=1.0` y arranca `progreso`. |
| `posicionEnCola` | Si `estado="encolado"`, indica el turno (1-based). Es 0 cuando ya corre o termino. |
| `canceladoPorUsuario` | `true` si el usuario llamo a `/cancelar`. Permite distinguir cancelacion voluntaria de fallo real sin parsear `error`. |
| `taPromedioMs` | Tiempo real promedio de procesamiento por bloque. Util para estimar ETA. |

Uso frontend:

- Polling cada 1-3 s mientras `estado` sea no terminal.
- Doble barra de progreso (warmup + ejecucion) cuando `fechaInicio` esta presente.
- Mostrar posicion en cola si `estado="encolado"`.
- Habilitar boton "cancelar" mientras el estado sea activo.

### GET `/jobs/{jobId}/bloques?desde=0`

Entrega bloques incrementales de simulacion desde el indice indicado. La respuesta es un **wrapper** con metadata sobre el total publicado y si el job ya termino.

Parametros:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `desde` | number | `0` | Primer bloque que se quiere recibir. |

Respuesta:

```json
{
  "bloques": [
    {
      "bloqueIdx": 0,
      "horaInicio": "2026-05-19T08:00:00",
      "horaFin": "2026-05-19T09:00:00",
      "maletasProcesadas": 120,
      "maletasEnrutadas": 115,
      "maletasProcesadasAcum": 120,
      "maletasEnrutadasAcum": 115,
      "maletasEntregadasAcum": 90,
      "asignaciones": [],
      "cargasVuelos": [],
      "ocupacionAlmacenes": [],
      "taMs": 1500,
      "scMinutos": 60,
      "tiempoProcesamientoMs": 2100
    }
  ],
  "total": 12,
  "terminado": false
}
```

Campos del wrapper:

- `bloques`: lista de `BloqueSimulacion` con `bloqueIdx >= desde`.
- `total`: cantidad total de bloques publicados hasta ahora por el job.
- `terminado`: `true` cuando el job alcanzo un estado terminal (`completado`, `cancelado` o `error`). Si es `true` y ya no hay bloques nuevos, el front puede dejar de hacer polling.

Uso frontend:

- Timeline de simulacion con `bloqueIdx` como eje.
- Acumular `cargasVuelos` y `ocupacionAlmacenes` por bloque para mapa/dashboard.
- Pedir solo el delta usando `desde = ultimoRecibido + 1`.
- Parar polling cuando `terminado=true` y `bloques.length=0`.

### GET `/jobs/{jobId}/dashboard`

Read model liviano del estado consolidado del job, sin recalcular el motor. Usa el resultado final si existe, o las metricas derivadas de los bloques publicados si sigue corriendo.

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "escenario": "2",
  "algoritmo": "alns",
  "estado": "ejecutando",
  "k": 14,
  "seed": 42,
  "fechaInicio": "2026-05-19T08:00:00",
  "inicio": "2026-05-25T11:32:10",
  "fin": null,
  "progreso": 0.25,
  "progresoWarmup": 1.0,
  "bloqueActual": 12,
  "totalBloques": 48,
  "bloquesPublicados": 12,
  "posicionEnCola": 0,
  "canceladoPorUsuario": false,
  "error": null,
  "metricas": {
    "procesadas": 1200,
    "enrutadas": 1160,
    "sinRuta": 40,
    "cumpleSLA": 1100,
    "tardadas": 60,
    "backlogActual": 80,
    "backlogPico": 140,
    "vuelosCancelados": 3,
    "maletasIndividuales": 1200
  },
  "tasas": {
    "porcentajeEnrutadas": 96.67,
    "porcentajeSLA": 94.82,
    "porcentajeSinRuta": 3.33
  },
  "ultimoBloque": {
    "bloqueIdx": 11,
    "horaInicio": "2026-05-19T19:00:00",
    "horaFin": "2026-05-19T20:00:00"
  }
}
```

Uso frontend:

- Pantalla resumen / KPIs principales sin recalcular en cliente.
- Una sola llamada para alimentar header de progreso, metricas y ultimo bloque.

### GET `/jobs/{jobId}/indicadores`

Entrega los umbrales semaforo activos y el detalle desagregado de vuelos y almacenes (mismas filas que `/vuelos/carga` y `/almacenes/ocupacion`).

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "umbrales": {
    "verdeHasta": 0.70,
    "ambarHasta": 0.90
  },
  "vuelos": [ /* mismos rows que /vuelos/carga */ ],
  "almacenes": [ /* mismos rows que /almacenes/ocupacion */ ]
}
```

Uso frontend:

- Aplicar los umbrales `verdeHasta`/`ambarHasta` al renderizar barras de carga propias en caso de querer recolorearlas localmente.
- Vista combinada vuelos+almacenes en una sola llamada para pantallas de alerta.

### GET `/jobs/{jobId}/vuelos/carga`

Entrega carga asignada por vuelo a lo largo de **todos los bloques publicados** del job, no solo el ultimo. Cada fila trae el `bloqueIdx`, `horaInicio` y `horaFin` del bloque al que pertenece.

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "total": 320,
  "vuelos": [
    {
      "bloqueIdx": 0,
      "horaInicio": "2026-05-19T08:00:00",
      "horaFin": "2026-05-19T09:00:00",
      "vueloId": "LA2450",
      "origen": "SPIM",
      "destino": "SKBO",
      "fechaSalida": "2026-05-19T10:00:00",
      "fechaLlegada": "2026-05-19T13:00:00",
      "capacidadMaxima": 180,
      "cargaAsignada": 145,
      "porcentajeCarga": 80.56,
      "semaforo": "AMBAR"
    }
  ]
}
```

Uso frontend:

- Tabla de vuelos con filtrado por `semaforo` y `bloqueIdx`.
- Series temporales de ocupacion por vuelo.
- Detectar saturacion / sobrecarga de vuelo (RF62).

### GET `/jobs/{jobId}/almacenes/ocupacion`

Entrega ocupacion por almacen/aeropuerto a lo largo de los bloques publicados. Misma estructura que `vuelos/carga`.

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "total": 540,
  "almacenes": [
    {
      "bloqueIdx": 0,
      "horaInicio": "2026-05-19T08:00:00",
      "horaFin": "2026-05-19T09:00:00",
      "aeropuerto": "SPIM",
      "fecha": "2026-05-19T10:00:00",
      "capacidadMaxima": 1000,
      "ocupacionAsignada": 720,
      "porcentajeOcupacion": 72.0,
      "semaforo": "AMBAR"
    }
  ]
}
```

Uso frontend:

- Mapa de aeropuertos coloreado por `semaforo` por bloque.
- Ranking de almacenes saturados.
- Alertas por sobrecarga de almacen (RF61).

### GET `/jobs/{jobId}/asignaciones`

Entrega asignaciones de maletas con filtros server-side. Cada fila contiene el bloque al que pertenece la asignacion.

Parametros:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `desde` | number | `0` | Bloque inicial. |
| `aeropuerto` | string | opcional | Filtra por origen o destino del lote (case-insensitive, normalizado). |
| `vueloId` | string | opcional | Filtra asignaciones cuya ruta usa ese vuelo. |
| `soloEnrutadas` | boolean | `false` | Si es `true`, retorna solo asignaciones con ruta. |

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "desde": 0,
  "aeropuerto": "SPIM",
  "vueloId": null,
  "soloEnrutadas": true,
  "total": 48,
  "asignaciones": [
    {
      "bloqueIdx": 0,
      "horaInicio": "2026-05-19T08:00:00",
      "horaFin": "2026-05-19T09:00:00",
      "asignacion": {
        "batchId": "BATCH-001",
        "origen": "SPIM",
        "destino": "SKBO",
        "cantidad": 25,
        "enrutada": true,
        "cumpleSLA": true,
        "rutaVuelos": ["LA2450", "LA3001"],
        "tramos": [
          {
            "vueloId": "LA2450",
            "origen": "SPIM",
            "destino": "SKBO",
            "salidaLocal": "2026-05-19T10:00:00",
            "llegadaLocal": "2026-05-19T13:00:00"
          }
        ]
      }
    }
  ]
}
```

Uso frontend:

- Tabla / detalle de rutas por lote.
- Debug visual de maletas sin ruta (`soloEnrutadas=false`).
- Trazabilidad de vuelos usados (filtro `vueloId`).

Nota: los campos `salidaUtc` y `llegadaUtc` aun aparecen por compatibilidad, pero estan **deprecados** — son `LocalDateTime` sin offset, no UTC reales. Preferir `salidaLocal` / `llegadaLocal`.

### GET `/jobs/{jobId}/resultado`

Entrega el resultado completo final o parcial disponible.

Respuesta principal:

```json
{
  "metricas": {
    "procesadas": 1200,
    "enrutadas": 1160,
    "sinRuta": 40,
    "cumpleSLA": 1100,
    "tardadas": 60,
    "maletasIndividuales": 1200,
    "vuelosCancelados": 3,
    "tiempoEjecucionMs": 45000,
    "collapsoDetectado": false,
    "bloqueColapso": null,
    "taMinMs": 900,
    "taMaxMs": 3200,
    "taPromedioMs": 1800,
    "tiempoTotalAlgMs": 30000,
    "advertenciaCalibracion": null,
    "backlogActual": 80,
    "backlogPico": 140,
    "sinRutaDefinitivo": 40
  },
  "totalBloques": 48,
  "vuelosPlaneados": [],
  "aeropuertosInfo": [],
  "k": 14,
  "saMinutos": 60
}
```

Uso frontend:

- Pantalla final de resultados.
- Exportaciones.
- Resumen de simulacion terminada.

### POST `/jobs/{jobId}/cancelar`

Solicita la cancelacion cooperativa de un job. El cambio de estado a `cancelado` no es inmediato; el job termina su bloque actual antes de marcar `estado="cancelado"` y `canceladoPorUsuario=true`.

Respuesta:

```json
{
  "jobId": "esc2-abc123",
  "cancelado": true
}
```

`cancelado=false` indica que el job no existia o ya estaba en estado terminal. Para el estado real, consultar `/jobs/{jobId}/estado` despues de cancelar.

Uso frontend:

- Boton cancelar simulacion.
- Deshabilitar cuando el job ya esta en estado terminal.

### GET `/jobs/{jobId}/muestra.csv`

Descarga CSV de muestra del job.

Headers relevantes:

| Header | Descripcion |
| --- | --- |
| `Content-Disposition` | Nombre sugerido del archivo. |
| `X-Muestra-Rows` | Cantidad de filas exportadas, si aplica. |

Uso frontend:

- Descargar como blob.
- No parsear como JSON.

### GET `/jobs/{jobId}/auditoria.csv`

Descarga CSV de auditoria del job.

Headers relevantes:

| Header | Descripcion |
| --- | --- |
| `Content-Disposition` | Nombre sugerido del archivo. |
| `X-Audit-Rows` | Cantidad de filas exportadas, si aplica. |

Uso frontend:

- Descargar trazabilidad tecnica.
- No parsear como JSON.

## Demanda

### GET `/demanda/resumen`

Entrega resumen de demanda agregada sobre el dataset cargado. Si no se envian `desde`/`hasta`, usa todo el rango disponible (`primeraVentana` a `ultimaVentana + 1 min`).

Parametros:

| Nombre | Tipo | Default | Descripcion |
| --- | --- | --- | --- |
| `desde` | string | opcional | Inicio de rango ISO local. |
| `hasta` | string | opcional | Fin de rango ISO local. |
| `top` | number | `20` | Maximo de filas por grupo (clamp 1..200). |

Respuesta:

```json
{
  "desde": "2026-05-19T00:00:00",
  "hasta": "2026-05-22T00:00:00",
  "top": 20,
  "totalEnvios": 1280,
  "totalMaletas": 5230,
  "porOrigen": [
    { "clave": "SPIM", "envios": 320, "maletas": 1480 }
  ],
  "porDestino": [
    { "clave": "SKBO", "envios": 280, "maletas": 1200 }
  ],
  "porOD": [
    { "clave": "SPIM->SKBO", "envios": 110, "maletas": 420 }
  ]
}
```

Si el rango es invalido (no hay datos o `hasta <= desde`), responde con `totalEnvios=0`, `totalMaletas=0` y listas vacias.

Uso frontend:

- Vista previa de demanda antes de iniciar simulacion.
- Graficas top origen / destino / ruta OD.
- Filtros antes de fijar `fechaInicio` o `dias`.

## Comparativa

Base path: `/api/planificador/comparativa`

### POST `/comparativa/run`

Ejecuta comparativa entre motores o configuraciones.

Body opcional:

```json
{
  "k": 14,
  "cancelProb": 0.0,
  "repeticiones": 30,
  "seedBase": 42,
  "motor": "ambos",
  "algoritmo": "alns",
  "ejecutarColapso": false,
  "umbralColapso": 0.2,
  "fechaInicio": "2026-05-19T08:00:00",
  "sa": 60,
  "ta": 60,
  "dias": 2
}
```

Respuesta:

```json
{
  "jobId": "cmp-abc123",
  "estado": "encolado"
}
```

### GET `/comparativa/{jobId}/estado`

Consulta estado de la comparativa.

Respuesta:

```json
{
  "jobId": "cmp-abc123",
  "estado": "ejecutando",
  "filasTotales": 60,
  "filasCompletadas": 12,
  "configActual": "alns-k14-rep12",
  "error": null
}
```

### GET `/comparativa/{jobId}/resultado`

Entrega resultado JSON de comparativa.

Respuesta:

```json
{
  "jobId": "cmp-abc123",
  "estado": "completado",
  "error": null,
  "inicio": "2026-05-19T08:00:00",
  "fin": "2026-05-19T08:10:00",
  "filasTotales": 60,
  "filasCompletadas": 60,
  "configActual": null,
  "filas": []
}
```

### GET `/comparativa/{jobId}/resultado.csv`

Descarga CSV de comparativa.

Headers relevantes:

| Header | Descripcion |
| --- | --- |
| `Content-Disposition` | Nombre sugerido del archivo. |
| `X-Rows` | Filas exportadas. |
| `X-Estado` | Estado del job al momento de descargar. |

## Benchmark

Base path: `/api/planificador/benchmark`

### POST `/benchmark/run`

Ejecuta benchmark de parametros.

Body opcional:

```json
{
  "kGrid": [14, 30, 75],
  "cancelProbGrid": [0.0, 0.1],
  "repeticiones": 10,
  "ejecutarColapso": false,
  "umbralColapso": 0.2
}
```

Respuesta:

```json
{
  "jobId": "bench-abc123",
  "estado": "encolado"
}
```

### GET `/benchmark/{jobId}/estado`

Consulta estado del benchmark.

Respuesta:

```json
{
  "jobId": "bench-abc123",
  "estado": "ejecutando",
  "filasTotales": 60,
  "filasCompletadas": 20,
  "configActual": "k14-cancel0.1-rep3",
  "error": null
}
```

### GET `/benchmark/{jobId}/resultado`

Entrega resultado JSON del benchmark.

Respuesta:

```json
{
  "jobId": "bench-abc123",
  "estado": "completado",
  "error": null,
  "inicio": "2026-05-19T08:00:00",
  "fin": "2026-05-19T09:00:00",
  "filasTotales": 60,
  "filasCompletadas": 60,
  "configActual": null,
  "filas": [],
  "recomendacion": {
    "k": 14,
    "cancelProb": 0.0,
    "motivo": "Mejor balance entre SLA y tiempo"
  }
}
```

## Endpoints legacy

Estos endpoints existen y pueden servir para pruebas rapidas, pero no son los recomendados para UI nueva porque no siguen el flujo por `jobId`.

### GET `/ejecutar`

Ejecuta simulacion sincronica o legacy.

Parametros:

| Nombre | Tipo | Default |
| --- | --- | --- |
| `algoritmo` | string | `alns` |
| `k` | number | `14` |
| `cancelProb` | number | `0.0` |

### GET `/bloque/{index}`

Obtiene bloque legacy por indice.

### GET `/ejecutar-colapso`

Ejecuta escenario legacy de colapso.

Parametros:

| Nombre | Tipo | Default |
| --- | --- | --- |
| `k` | number | `75` |
| `cancelProb` | number | `0.1` |
| `umbralColapso` | number | `0.20` |

## Interfaces TypeScript sugeridas

```ts
export type JobEstado =
  | "encolado"
  | "calentando"
  | "ejecutando"
  | "completado"
  | "cancelado"
  | "error";

export type Semaforo = "VERDE" | "AMBAR" | "ROJO";

export interface JobStartResponse {
  jobId: string;
  estado: JobEstado;
}

export interface MetricasSimulacion {
  procesadas: number;
  enrutadas: number;
  sinRuta: number;
  cumpleSLA: number;
  tardadas: number;
  maletasIndividuales: number;
  vuelosCancelados: number;
  tiempoEjecucionMs: number;
  collapsoDetectado: boolean;
  bloqueColapso?: number | null;
  taMinMs?: number | null;
  taMaxMs?: number | null;
  taPromedioMs?: number | null;
  tiempoTotalAlgMs?: number | null;
  advertenciaCalibracion?: string | null;
  backlogActual?: number | null;
  backlogPico?: number | null;
  sinRutaDefinitivo?: number | null;
}

export interface CargaVuelo {
  vueloId: string;
  origen: string;
  destino: string;
  fechaSalida: string;
  fechaLlegada: string;
  capacidadMaxima: number;
  cargaAsignada: number;
  porcentajeCarga: number;
  semaforo: Semaforo;
}

export interface OcupacionAlmacen {
  aeropuerto: string;
  fecha: string;
  capacidadMaxima: number;
  ocupacionAsignada: number;
  porcentajeOcupacion: number;
  semaforo: Semaforo;
}

export interface TramoRuta {
  vueloId: string;
  origen: string;
  destino: string;
  salidaLocal: string;
  llegadaLocal: string;
  salidaUtc?: string;
  llegadaUtc?: string;
}

export interface AsignacionMaleta {
  batchId: string;
  origen: string;
  destino: string;
  cantidad: number;
  enrutada: boolean;
  cumpleSLA: boolean;
  rutaVuelos: string[];
  tramos: TramoRuta[];
}

export interface BloqueSimulacion {
  bloqueIdx: number;
  horaInicio: string;
  horaFin: string;
  maletasProcesadas: number;
  maletasEnrutadas: number;
  maletasProcesadasAcum: number;
  maletasEnrutadasAcum: number;
  maletasEntregadasAcum: number;
  asignaciones: AsignacionMaleta[];
  cargasVuelos: CargaVuelo[];
  ocupacionAlmacenes: OcupacionAlmacen[];
  taMs?: number | null;
  scMinutos?: number | null;
  tiempoProcesamientoMs?: number | null;
}

export interface VueloPlaneado {
  id: string;
  origen: string;
  destino: string;
  fechaSalida: string;
  fechaLlegada: string;
  capacidadMaxima: number;
  cargaAsignada: number;
}

export interface AeropuertoInfo {
  codigo: string;
  latitud: number;
  longitud: number;
  capacidadAlmacen: number | null;
}

export interface SimulacionResponse {
  metricas: MetricasSimulacion;
  totalBloques: number;
  vuelosPlaneados: VueloPlaneado[];
  /** Mapa indexado por codigo de aeropuerto. */
  aeropuertosInfo: Record<string, AeropuertoInfo>;
  k: number;
  saMinutos: number;
}

export interface JobEstadoResponse {
  jobId: string;
  escenario: string;
  algoritmo: string;
  seed: number | null;
  fechaInicio?: string;
  k: number;
  estado: JobEstado;
  bloqueActual: number;
  totalBloques: number;
  progreso: number;
  bloqueWarmup: number;
  totalBloquesWarmup: number;
  progresoWarmup: number;
  posicionEnCola: number;
  canceladoPorUsuario: boolean;
  taPromedioMs: number | null;
  inicio: string;
  fin?: string;
  error?: string;
}

export interface BloquesResponse {
  bloques: BloqueSimulacion[];
  total: number;
  terminado: boolean;
}
```

## Ejemplos de consumo

### Iniciar escenario 2

```ts
const res = await fetch(
  "http://localhost:8080/api/planificador/escenario2/iniciar?k=14&algoritmo=alns",
  { method: "POST" }
);

if (!res.ok) throw new Error("No se pudo iniciar escenario 2");

const job = (await res.json()) as JobStartResponse;
```

### Polling de estado

```ts
async function getJobEstado(jobId: string) {
  const res = await fetch(
    `http://localhost:8080/api/planificador/jobs/${jobId}/estado`
  );

  if (!res.ok) throw new Error("No se pudo consultar el estado");
  return res.json();
}
```

### Cargar bloques incrementales

```ts
async function getBloques(jobId: string, desde: number) {
  const res = await fetch(
    `http://localhost:8080/api/planificador/jobs/${jobId}/bloques?desde=${desde}`
  );

  if (!res.ok) throw new Error("No se pudieron cargar bloques");
  return (await res.json()) as BloquesResponse;
}
```

### Descargar CSV

```ts
async function descargarAuditoria(jobId: string) {
  const res = await fetch(
    `http://localhost:8080/api/planificador/jobs/${jobId}/auditoria.csv`
  );

  if (!res.ok) throw new Error("No se pudo descargar auditoria");

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `auditoria-${jobId}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
```

## Errores

Formato general recomendado para consumo frontend:

- Si `response.ok` es `false`, mostrar mensaje generico y registrar el body si existe.
- Si el job queda en estado `error`, mostrar el campo `error`.
- Si el backend responde CSV, no intentar parsear como JSON.
- Si una fecha viene `null`, mostrar `-` o dejar el campo vacio.

Codigos HTTP esperados:

| Codigo | Caso |
| --- | --- |
| `200` | Consulta correcta o CSV disponible. |
| `202` | Job aceptado o accion asincrona aceptada, si aplica. |
| `400` | Parametros invalidos. |
| `404` | `jobId` no encontrado. |
| `500` | Error interno de ejecucion. |

## Recomendaciones para el frontend

- Usar los endpoints por `jobId` para toda pantalla nueva.
- Usar `bloques?desde=` para actualizaciones incrementales.
- Usar `dashboard` e `indicadores` para KPIs, en vez de recalcular todo en cliente.
- Usar `vuelos/carga` y `almacenes/ocupacion` para tablas, mapas y alertas de saturacion.
- Mantener filtros de asignaciones server-side con `aeropuerto`, `vueloId` y `soloEnrutadas` para no descargar datos excesivos.
- Preferir `salidaLocal` y `llegadaLocal` en rutas.

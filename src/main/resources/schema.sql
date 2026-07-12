-- =====================================================================================
-- schema.sql — DDL baseline del Backend-Planificador-TASFB2B (PostgreSQL)
-- =====================================================================================

-- ── aeropuerto ───────────────────────────────────────────────────────────────────────
-- 30 aeropuertos: ICAO, huso horario (GMT offset), capacidad de almacén, lat/lon.
CREATE TABLE IF NOT EXISTS aeropuerto (
    icao              VARCHAR          PRIMARY KEY,           -- código ICAO (PK)
    ciudad            VARCHAR,
    pais              VARCHAR,
    codigo_region     VARCHAR,                                -- el back la usa como "abreviatura"
    huso_horario      INTEGER,                                -- GMT offset (p. ej. -5, 0, +8)
    capacidad_almacen INTEGER,                                -- maletas concurrentes en almacén
    capacidad_almacen_original INTEGER,                       -- valor original de capacidad_almacen
    latitud           DOUBLE PRECISION,
    longitud          DOUBLE PRECISION,
    activo            BOOLEAN          NOT NULL DEFAULT TRUE,
    id_numero         VARCHAR                                 -- heredado, nullable, no usado por el back
);

-- ── vuelo ────────────────────────────────────────────────────────────────────────────
-- ~2.866 vuelos diarios que se repiten cada día. hora_salida/hora_llegada son HORA LOCAL
-- (varchar "HH:MM"); el back las normaliza a UTC en RAM (AlgorithmMapper).
CREATE TABLE IF NOT EXISTS vuelo (
    id_vuelo         VARCHAR  PRIMARY KEY,                    -- p. ej. "SKBO-SEQM-0830" (PK)
    icao_origen      VARCHAR  REFERENCES aeropuerto(icao),
    icao_destino     VARCHAR  REFERENCES aeropuerto(icao),
    hora_salida      VARCHAR,                                 -- hora LOCAL del origen, "HH:MM"
    hora_llegada     VARCHAR,                                 -- hora LOCAL del destino, "HH:MM"
    capacidad_maxima INTEGER,
    capacidad_maxima_original INTEGER
);

-- ── envio ────────────────────────────────────────────────────────────────────────────
-- Envío lógico = lote de N maletas con mismo origen/destino/registro. fecha_hora_registro
-- es HORA LOCAL del origen (el back deriva el UTC restando el huso del origen).
CREATE TABLE IF NOT EXISTS envio (
    id_envio            VARCHAR    PRIMARY KEY,               -- p. ej. "SKBO-12345" (PK)
    icao_origen         VARCHAR    REFERENCES aeropuerto(icao),
    icao_destino        VARCHAR    REFERENCES aeropuerto(icao),
    cantidad_maletas     INTEGER,
    id_cliente           INTEGER,                             -- Cliente es POJO, no hay tabla cliente
    fecha_hora_registro  TIMESTAMP,                           -- hora LOCAL del origen
    estado               VARCHAR,                             -- presente en la BD, NO mapeada por el back (huérfana)
    fecha_limite_entrega TIMESTAMP                            -- presente en la BD, NO mapeada por el back (huérfana)
);
-- Índice por fecha de registro: DataLoader.getMaletasEnRango filtra la demanda por esta
-- columna (la "columna local indexada" que cita CLAUDE.md §4/§6).
CREATE INDEX IF NOT EXISTS ix_envio_fecha_registro ON envio (fecha_hora_registro);

-- ── ruta_asignada ────────────────────────────────────────────────────────────────────
-- Una ruta calculada para un envío. Se guarda histórico (varias por envío); SOLO una con
-- activa=TRUE garantizada por el índice parcial único de abajo.
CREATE TABLE IF NOT EXISTS ruta_asignada (
    id_ruta        INTEGER          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_envio       VARCHAR          NOT NULL REFERENCES envio(id_envio) ON DELETE CASCADE,
    activa         BOOLEAN          NOT NULL DEFAULT TRUE,
    costo_total    DOUBLE PRECISION,                          -- proxy de tránsito total (min)
    duracion_horas DOUBLE PRECISION,
    cumple_sla     BOOLEAN,
    slack_sla_min  INTEGER,                                   -- holgura SLA en minutos
    llegada_utc    TIMESTAMP,                                 -- llegada UTC del último tramo
    fecha_calculo  TIMESTAMP        NOT NULL DEFAULT now()    -- el INSERT de la persistencia la omite
);
-- Índice por envío (lookups por id_envio) + invariante clave: como máximo una ruta activa por
-- (envío, sub_lote) (lo asume SolucionBdReader / PersistenciaSolucionService). El índice único
-- lo crea la sección de fragmentación (más abajo): es COMPUESTO (id_envio, sub_lote) y no puede
-- vivir aquí porque la columna sub_lote se agrega por ALTER después. NO recrear aquí el índice de
-- una sola columna: chocaría con BD que ya tengan sub-lotes activos del mismo padre.
CREATE INDEX        IF NOT EXISTS ix_ruta_por_envio        ON ruta_asignada (id_envio);

-- ── tramo_ruta ───────────────────────────────────────────────────────────────────────
-- Un vuelo dentro de una ruta, ordenado por numero_orden (0,1,2...). id_vuelo en formato
-- normalizado "ICAO-ICAO-HHMM" (sin los dos puntos de la hora). Tiempos en UTC.
CREATE TABLE IF NOT EXISTS tramo_ruta (
    id_tramo         INTEGER    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_ruta          INTEGER    NOT NULL REFERENCES ruta_asignada(id_ruta) ON DELETE CASCADE,
    numero_orden     INTEGER    NOT NULL,
    id_vuelo         VARCHAR    REFERENCES vuelo(id_vuelo) ON DELETE CASCADE,
    hora_salida_utc  TIMESTAMP,                               -- despegue UTC real
    hora_llegada_utc TIMESTAMP                                -- aterrizaje UTC real
);
-- Acelera afectadosPorVuelo (cancelación en vivo) y buscarPorEnvio: filtra por id_vuelo y
-- recompone los tramos de una ruta.
CREATE INDEX IF NOT EXISTS ix_tramo_id_ruta ON tramo_ruta (id_ruta);
CREATE INDEX IF NOT EXISTS ix_tramo_id_vuelo ON tramo_ruta (id_vuelo);

-- ── cancelacion_vuelo ────────────────────────────────────────────────────────────────
-- Cancelación de un vuelo-recurrente en un día concreto (modelo "vuelo-día"). La escribe
-- PersistenciaSolucionService.persistirCancelaciones cuando el usuario cancela un vuelo EN VIVO
-- (id_vuelo normalizado a ICAO-ICAO-HHMM ⇒ FK a vuelo OK). Se vacía al iniciar otra corrida
-- (TRUNCATE en iniciarCorrida): solo refleja las cancelaciones de la corrida vigente.
CREATE TABLE IF NOT EXISTS cancelacion_vuelo (
    id_cancelacion    INTEGER  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_vuelo          VARCHAR  NOT NULL REFERENCES vuelo(id_vuelo) ON DELETE CASCADE,
    fecha_cancelacion DATE     NOT NULL,
    envios_afectados  INTEGER  NOT NULL DEFAULT 0   -- envíos devueltos al backlog por la cancelación
);
-- Migración sobre una BD existente (la columna se añadió después del CREATE original):
--   ALTER TABLE cancelacion_vuelo ADD COLUMN envios_afectados INTEGER NOT NULL DEFAULT 0;

-- ── envio_inyectado ──────────────────────────────────────────────────────────────────
-- Envíos agregados EN VIVO por el operador durante una corrida (no pertenecen al dataset
-- maestro ENVIO). Se vacía al iniciar otra corrida (TRUNCATE en PersistenciaSolucionService),
-- por eso "solo valen para esa simulación". id_envio es sintético ("INV-bloque-n") y NO
-- referencia envio(id_envio) (a propósito: el inyectado no existe en el dataset maestro).
-- También es la tabla de la OPERACIÓN día a día EN VIVO del E1 ("caja registradora", enVivo=true): el
-- registro manual y la carga TXT escriben aquí (no en el dataset maestro ENVIO), por eso registrador/sede.
CREATE TABLE IF NOT EXISTS envio_inyectado (
    id_inyeccion     INTEGER   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_envio         VARCHAR   NOT NULL,                    -- id sintético "INV-bloque-n"
    icao_origen      VARCHAR   REFERENCES aeropuerto(icao),
    icao_destino     VARCHAR   REFERENCES aeropuerto(icao),
    cantidad_maletas INTEGER,
    id_cliente       INTEGER,                              -- Cliente es POJO, no hay tabla cliente
    ready_time_utc   TIMESTAMP,                            -- readyTime efectivo (UTC)
    sla_horas        INTEGER,                              -- 24 (intra) o 48 (inter)
    bloque_idx       INTEGER,                              -- bloque en que entró a la simulación
    registrador      VARCHAR,                              -- E1 operación: empleado que registró (opcional)
    sede             VARCHAR                               -- E1 operación: sede del registrador (opcional)
);
-- Migración sobre una BD existente (columnas añadidas después del CREATE original):
--   ALTER TABLE envio_inyectado ADD COLUMN registrador VARCHAR;
--   ALTER TABLE envio_inyectado ADD COLUMN sede        VARCHAR;

-- ── ruta_inyectada / tramo_inyectado ─────────────────────────────────────────────────
-- Ruta calculada de un envío INYECTADO EN VIVO (INV-*). Espejan ruta_asignada/tramo_ruta pero SIN FK
-- a envio (el sintético no existe en el dataset maestro): su id_envio es "INV-bloque-n" y sus
-- metadatos (origen/destino/cantidad/readyTime UTC) viven en envio_inyectado. Las escribe
-- PersistenciaSolucionService.persistirBloque (carril de los sintéticos) y las lee SolucionBdReader
-- (auditoría forEachEnrutado + rastreo buscarPorEnvioInyectado). Se vacían por corrida junto con
-- envio_inyectado (TRUNCATE en iniciarCorrida) ⇒ solo reflejan la corrida vigente.
CREATE TABLE IF NOT EXISTS ruta_inyectada (
    id_ruta_iny    INTEGER          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_envio       VARCHAR          NOT NULL,                  -- id sintético "INV-bloque-n" (sin FK)
    activa         BOOLEAN          NOT NULL DEFAULT TRUE,
    costo_total    DOUBLE PRECISION,                           -- proxy de tránsito total (min)
    duracion_horas DOUBLE PRECISION,
    cumple_sla     BOOLEAN,
    slack_sla_min  INTEGER,
    llegada_utc    TIMESTAMP,
    fecha_calculo  TIMESTAMP        NOT NULL DEFAULT now()     -- el INSERT de la persistencia la omite
);
-- Lookups por envío + invariante: como máximo una ruta activa por (envío sintético, sub_lote). El
-- índice único COMPUESTO lo crea la sección de fragmentación (más abajo), tras el ALTER de sub_lote.
-- NO recrear aquí el índice de una sola columna: chocaría con BD que ya tengan sub-lotes activos
-- del mismo padre inyectado (p. ej. dos filas INV-1-0 con sub_lote 1 y 2).
CREATE INDEX        IF NOT EXISTS ix_ruta_iny_por_envio        ON ruta_inyectada (id_envio);

CREATE TABLE IF NOT EXISTS tramo_inyectado (
    id_tramo_iny     INTEGER    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_ruta_iny      INTEGER    NOT NULL REFERENCES ruta_inyectada(id_ruta_iny) ON DELETE CASCADE,
    numero_orden     INTEGER    NOT NULL,
    id_vuelo         VARCHAR    REFERENCES vuelo(id_vuelo) ON DELETE CASCADE,   -- vuelo real del dataset
    hora_salida_utc  TIMESTAMP,
    hora_llegada_utc TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_tramo_iny_id_ruta ON tramo_inyectado (id_ruta_iny);

-- Migraciones adicionales (ejecutadas automáticamente)
ALTER TABLE aeropuerto ADD COLUMN IF NOT EXISTS capacidad_almacen_original INTEGER;
ALTER TABLE vuelo ADD COLUMN IF NOT EXISTS capacidad_maxima_original INTEGER;

-- Backfill idempotente del valor original: solo rellena NULL (nunca pisa un valor existente). Deja
-- consistente cualquier BD que tenga las columnas vacías, sin pasos manuales. El reset por corrida
-- (ConfiguracionCapacidadesService) filtra por capacidad_*_original IS NOT NULL, así que sin este
-- backfill el reset no restauraría nada.
UPDATE aeropuerto SET capacidad_almacen_original = capacidad_almacen WHERE capacidad_almacen_original IS NULL;
UPDATE vuelo      SET capacidad_maxima_original  = capacidad_maxima  WHERE capacidad_maxima_original  IS NULL;

-- ── Plan de vuelo modificable EN FRÍO (prueba E1 "día a día") ────────────────────────
-- PUT /configuracion/vuelos/{id}/horario cambia hora_salida/hora_llegada y
-- PUT /configuracion/vuelos/{id}/destino cambia icao_destino, ambos EN FRÍO (persistente, solo sin
-- simulación en curso); estas columnas guardan el valor de fábrica para
-- POST /configuracion/vuelos/restaurar-horarios. El id_vuelo SÍ se renombra al cambiar salida o
-- destino (invariante id_vuelo ≡ ORIGEN-DESTINO-HHMM de la salida vigente); como la PK no tiene
-- ON UPDATE CASCADE, el servicio despeja antes las FKs (tramo_*/cancelacion_vuelo de la corrida
-- anterior) — ver ConfiguracionCapacidadesService.borrarReferenciasSolucion.
ALTER TABLE vuelo ADD COLUMN IF NOT EXISTS hora_salida_original   VARCHAR;
ALTER TABLE vuelo ADD COLUMN IF NOT EXISTS hora_llegada_original  VARCHAR;
ALTER TABLE vuelo ADD COLUMN IF NOT EXISTS icao_destino_original  VARCHAR;
UPDATE vuelo SET hora_salida_original   = hora_salida   WHERE hora_salida_original   IS NULL;
UPDATE vuelo SET hora_llegada_original  = hora_llegada  WHERE hora_llegada_original  IS NULL;
UPDATE vuelo SET icao_destino_original  = icao_destino  WHERE icao_destino_original  IS NULL;

-- ── Altas EN CALIENTE (efímeras por corrida) ─────────────────────────────────────────
-- Vuelos/aeropuertos agregados EN VIVO por el operador durante una corrida. Existen como fila
-- real (las FKs de tramo_ruta/tramo_inyectado/cancelacion_vuelo exigen que el vuelo exista),
-- marcados efimero=TRUE. Se revierten al iniciar la corrida siguiente (AltasEnCalienteService).
ALTER TABLE aeropuerto ADD COLUMN IF NOT EXISTS efimero BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE vuelo      ADD COLUMN IF NOT EXISTS efimero BOOLEAN NOT NULL DEFAULT FALSE;

-- Limpieza al arranque: residuos de una corrida interrumpida (crash con altas activas). Corre
-- antes del @PostConstruct de CargadorDatos ⇒ la RAM nunca arranca con efímeros. Orden por FKs:
-- envio_inyectado→aeropuerto NO cascadea; vuelo→tramo_*/cancelacion_vuelo SÍ (ON DELETE CASCADE).
DELETE FROM envio_inyectado
 WHERE icao_origen  IN (SELECT icao FROM aeropuerto WHERE efimero)
    OR icao_destino IN (SELECT icao FROM aeropuerto WHERE efimero);
DELETE FROM vuelo WHERE efimero;
DELETE FROM aeropuerto WHERE efimero;

-- ── Fragmentación de envíos en sub-lotes (caso E1: cantidad > capacidad de avión) ─────
-- Un envío con cantidad mayor que la capacidad máxima de los aviones se parte en N sub-lotes AL
-- NACER; cada sub-lote es una ruta activa propia. sub_lote = 0 ⇒ envío NO fragmentado (semántica
-- previa exacta). Para fragmentados, id_envio = id del PADRE (conserva la FK a envio) y sub_lote =
-- 1..total_sub_lotes; cantidad = maletas ruteadas por ESTE sub-lote (COALESCE con la cantidad del
-- envío/inyección al leer, para las filas viejas donde la columna cantidad quedó NULL).
ALTER TABLE ruta_asignada  ADD COLUMN IF NOT EXISTS sub_lote        SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE ruta_asignada  ADD COLUMN IF NOT EXISTS total_sub_lotes SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE ruta_asignada  ADD COLUMN IF NOT EXISTS cantidad        INTEGER;
ALTER TABLE ruta_inyectada ADD COLUMN IF NOT EXISTS sub_lote        SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE ruta_inyectada ADD COLUMN IF NOT EXISTS total_sub_lotes SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE ruta_inyectada ADD COLUMN IF NOT EXISTS cantidad        INTEGER;

-- Invariante: como máximo UNA ruta activa por (envío, sub_lote). Reemplaza al índice que sólo
-- consideraba id_envio (rompería con 2 sub-lotes activos del mismo padre). Para envíos no
-- fragmentados (sub_lote = 0 constante) equivale al índice anterior.
DROP INDEX IF EXISTS ux_ruta_activa_por_envio;
CREATE UNIQUE INDEX IF NOT EXISTS ux_ruta_activa_por_envio_sublote
    ON ruta_asignada (id_envio, sub_lote) WHERE activa;
DROP INDEX IF EXISTS ux_ruta_iny_activa_por_envio;
CREATE UNIQUE INDEX IF NOT EXISTS ux_ruta_iny_activa_por_envio_sublote
    ON ruta_inyectada (id_envio, sub_lote) WHERE activa;

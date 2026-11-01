CREATE OR REPLACE FUNCTION public.road_render_min_zoom(p_road_type VARCHAR)
RETURNS SMALLINT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE COALESCE(p_road_type, '')
        WHEN 'motorway' THEN 2
        WHEN 'motorway_link' THEN 2
        WHEN 'trunk' THEN 2
        WHEN 'trunk_link' THEN 2
        WHEN 'primary' THEN 3
        WHEN 'primary_link' THEN 3
        WHEN 'secondary' THEN 4
        WHEN 'secondary_link' THEN 4
        WHEN 'tertiary' THEN 5
        WHEN 'tertiary_link' THEN 5
        WHEN 'residential' THEN 6
        WHEN 'unclassified' THEN 6
        WHEN 'road' THEN 6
        WHEN 'service' THEN 7
        WHEN 'living_street' THEN 7
        ELSE 8
    END::SMALLINT;
$$;

CREATE TABLE public.road_edge_lod (
    lod                 SMALLINT NOT NULL,
    edge_id             BIGINT NOT NULL,
    min_zoom            SMALLINT NOT NULL,
    osm_id              BIGINT,
    name                VARCHAR(200) NOT NULL,
    road_type           VARCHAR(50),
    oneway_raw          VARCHAR(20),
    driving_direction   VARCHAR(10) NOT NULL,
    walking_direction   VARCHAR(10) NOT NULL,
    maxspeed_forward    DOUBLE PRECISION,
    maxspeed_backward   DOUBLE PRECISION,
    geom                geometry(LineString, 3857) NOT NULL,

    PRIMARY KEY (lod, edge_id),
    CONSTRAINT road_edge_lod_level_ck CHECK (lod IN (2, 4, 6)),
    CONSTRAINT road_edge_lod_min_zoom_ck CHECK (min_zoom BETWEEN 2 AND 8)
) PARTITION BY LIST (lod);

CREATE TABLE public.road_edge_lod_2
    PARTITION OF public.road_edge_lod FOR VALUES IN (2);
CREATE TABLE public.road_edge_lod_4
    PARTITION OF public.road_edge_lod FOR VALUES IN (4);
CREATE TABLE public.road_edge_lod_6
    PARTITION OF public.road_edge_lod FOR VALUES IN (6);

CREATE INDEX road_edge_lod_2_geom_gix
    ON public.road_edge_lod_2 USING GIST (geom);
CREATE INDEX road_edge_lod_4_geom_gix
    ON public.road_edge_lod_4 USING GIST (geom);
CREATE INDEX road_edge_lod_6_geom_gix
    ON public.road_edge_lod_6 USING GIST (geom);

CREATE INDEX road_edge_lod_2_min_zoom_idx
    ON public.road_edge_lod_2 (min_zoom);
CREATE INDEX road_edge_lod_4_min_zoom_idx
    ON public.road_edge_lod_4 (min_zoom);
CREATE INDEX road_edge_lod_6_min_zoom_idx
    ON public.road_edge_lod_6 (min_zoom);

CREATE OR REPLACE FUNCTION public.refresh_road_edge_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    TRUNCATE TABLE public.road_edge_lod;

    INSERT INTO public.road_edge_lod (
        lod,
        edge_id,
        min_zoom,
        osm_id,
        name,
        road_type,
        oneway_raw,
        driving_direction,
        walking_direction,
        maxspeed_forward,
        maxspeed_backward,
        geom
    )
    SELECT
        level.lod,
        edge.id,
        visibility.min_zoom,
        edge.osm_id,
        edge.name,
        edge.road_type,
        edge.oneway_raw,
        edge.driving_direction,
        edge.walking_direction,
        edge.maxspeed_forward,
        edge.maxspeed_backward,
        ST_SimplifyPreserveTopology(edge.geom_local, level.tolerance_m)::geometry(LineString, 3857)
    FROM public.road_edge edge
    CROSS JOIN (VALUES
        (2::SMALLINT, 3::SMALLINT, 64.0::DOUBLE PRECISION),
        (4::SMALLINT, 5::SMALLINT, 16.0::DOUBLE PRECISION),
        (6::SMALLINT, 7::SMALLINT, 4.0::DOUBLE PRECISION)
    ) AS level(lod, maximum_zoom, tolerance_m)
    CROSS JOIN LATERAL (
        SELECT public.road_render_min_zoom(edge.road_type) AS min_zoom
    ) visibility
    WHERE edge.status = 'active'
      AND visibility.min_zoom <= level.maximum_zoom;

    ANALYZE public.road_edge_lod;
END;
$$;

SELECT public.refresh_road_edge_lod();

CREATE OR REPLACE FUNCTION public.get_local_road_vector_tile(
    p_z INTEGER,
    p_x INTEGER,
    p_y INTEGER
)
RETURNS BYTEA
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    WITH tile_bounds AS (
        SELECT
            public.local_tile_envelope(p_z, p_x, p_y) AS geom,
            262144.0 / power(2, p_z) AS tile_size
    ),
    source_feature AS (
        SELECT
            edge.edge_id AS id,
            edge.osm_id,
            edge.name,
            edge.road_type,
            edge.oneway_raw,
            edge.driving_direction,
            edge.walking_direction,
            edge.maxspeed_forward,
            edge.maxspeed_backward,
            edge.geom
        FROM public.road_edge_lod_2 edge
        CROSS JOIN tile_bounds bounds
        WHERE p_z BETWEEN 2 AND 3
          AND edge.min_zoom <= p_z
          AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

        UNION ALL

        SELECT
            edge.edge_id AS id,
            edge.osm_id,
            edge.name,
            edge.road_type,
            edge.oneway_raw,
            edge.driving_direction,
            edge.walking_direction,
            edge.maxspeed_forward,
            edge.maxspeed_backward,
            edge.geom
        FROM public.road_edge_lod_4 edge
        CROSS JOIN tile_bounds bounds
        WHERE p_z BETWEEN 4 AND 5
          AND edge.min_zoom <= p_z
          AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

        UNION ALL

        SELECT
            edge.edge_id AS id,
            edge.osm_id,
            edge.name,
            edge.road_type,
            edge.oneway_raw,
            edge.driving_direction,
            edge.walking_direction,
            edge.maxspeed_forward,
            edge.maxspeed_backward,
            edge.geom
        FROM public.road_edge_lod_6 edge
        CROSS JOIN tile_bounds bounds
        WHERE p_z BETWEEN 6 AND 7
          AND edge.min_zoom <= p_z
          AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

        UNION ALL

        SELECT
            edge.id,
            edge.osm_id,
            edge.name,
            edge.road_type,
            edge.oneway_raw,
            edge.driving_direction,
            edge.walking_direction,
            edge.maxspeed_forward,
            edge.maxspeed_backward,
            edge.geom_local AS geom
        FROM public.road_edge edge
        CROSS JOIN tile_bounds bounds
        WHERE p_z >= 8
          AND edge.status = 'active'
          AND edge.geom_local && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)
    ),
    tile_feature AS (
        SELECT
            source.id,
            source.osm_id,
            source.name,
            source.road_type,
            source.oneway_raw,
            source.driving_direction,
            source.walking_direction,
            source.maxspeed_forward,
            source.maxspeed_backward,
            ST_AsMVTGeom(source.geom, bounds.geom, 4096, 64, true) AS geom
        FROM source_feature source
        CROSS JOIN tile_bounds bounds
    ),
    valid_feature AS (
        SELECT *
        FROM tile_feature
        WHERE geom IS NOT NULL
          AND NOT ST_IsEmpty(geom)
    )
    SELECT COALESCE(
        ST_AsMVT(valid_feature, 'road', 4096, 'geom'),
        ''::BYTEA
    )
    FROM valid_feature;
$$;

COMMENT ON TABLE public.road_edge_lod IS
    'Offline generalized road geometry used only for low-zoom vector tiles';
COMMENT ON FUNCTION public.refresh_road_edge_lod() IS
    'Rebuilds low-zoom road rendering data from the authoritative road_edge table';

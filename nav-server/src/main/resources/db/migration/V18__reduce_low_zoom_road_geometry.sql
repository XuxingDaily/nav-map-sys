-- At z2-z7, sub-pixel geometry only increases MVT decoding and canvas work.
-- Build render geometry at roughly half a pixel at the highest zoom represented
-- by each LOD bucket, merge endpoint-connected fragments, and expose only the
-- properties needed to paint low-zoom roads.
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
        WHEN 'primary' THEN 4
        WHEN 'primary_link' THEN 4
        WHEN 'secondary' THEN 6
        WHEN 'secondary_link' THEN 6
        WHEN 'tertiary' THEN 7
        WHEN 'tertiary_link' THEN 7
        WHEN 'residential' THEN 8
        WHEN 'unclassified' THEN 8
        WHEN 'road' THEN 8
        WHEN 'service' THEN 9
        WHEN 'living_street' THEN 9
        ELSE 10
    END::SMALLINT;
$$;

CREATE OR REPLACE FUNCTION public.refresh_road_edge_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    TRUNCATE TABLE public.road_edge_lod;

    WITH generalized AS (
        SELECT
            level.lod,
            visibility.min_zoom,
            level.tolerance_m,
            level.cell_size_m,
            COALESCE(edge.road_type, 'road') AS road_type,
            ST_SimplifyPreserveTopology(edge.geom_local, level.tolerance_m) AS geom
        FROM public.road_edge edge
        CROSS JOIN (VALUES
            -- About 0.4-0.5 screen pixels at the highest zoom in the bucket.
            (2::SMALLINT, 3::SMALLINT, 8192.0::DOUBLE PRECISION, 32768.0::DOUBLE PRECISION),
            (4::SMALLINT, 5::SMALLINT, 2048.0::DOUBLE PRECISION, 8192.0::DOUBLE PRECISION),
            (6::SMALLINT, 7::SMALLINT, 512.0::DOUBLE PRECISION, 2048.0::DOUBLE PRECISION)
        ) AS level(lod, maximum_zoom, tolerance_m, cell_size_m)
        CROSS JOIN LATERAL (
            SELECT public.road_render_min_zoom(edge.road_type) AS min_zoom
        ) visibility
        WHERE edge.status = 'active'
          AND visibility.min_zoom <= level.maximum_zoom
    ),
    located AS (
        SELECT
            *,
            FLOOR((ST_X(ST_Centroid(geom)) - 12550000.0) / cell_size_m)::INTEGER AS cell_x,
            FLOOR((2800000.0 - ST_Y(ST_Centroid(geom))) / cell_size_m)::INTEGER AS cell_y
        FROM generalized
        WHERE NOT ST_IsEmpty(geom)
    ),
    aggregated AS (
        SELECT
            lod,
            min_zoom,
            road_type,
            cell_x,
            cell_y,
            ST_CollectionExtract(
                ST_SimplifyPreserveTopology(
                    ST_LineMerge(ST_CollectionExtract(ST_Collect(geom), 2)),
                    MIN(tolerance_m)
                ),
                2
            )::geometry(Geometry, 3857) AS geom
        FROM located
        GROUP BY lod, min_zoom, road_type, cell_x, cell_y
    ),
    numbered AS (
        SELECT
            ROW_NUMBER() OVER (
                PARTITION BY lod
                ORDER BY cell_y, cell_x, min_zoom, road_type
            )::BIGINT AS render_edge_id,
            *
        FROM aggregated
        WHERE NOT ST_IsEmpty(geom)
    )
    INSERT INTO public.road_edge_lod (
        lod, edge_id, min_zoom, osm_id, name, road_type, oneway_raw,
        driving_direction, walking_direction, maxspeed_forward,
        maxspeed_backward, geom
    )
    SELECT
        lod, render_edge_id, min_zoom, NULL, road_type, road_type, NULL,
        'MIXED', 'MIXED', NULL, NULL, geom
    FROM numbered;

    ANALYZE public.road_edge_lod;
END;
$$;

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
    )
    SELECT CASE
        WHEN p_z BETWEEN 2 AND 7 THEN (
            WITH source_feature AS (
                SELECT edge.edge_id AS id, edge.road_type, edge.geom
                FROM public.road_edge_lod_2 edge
                CROSS JOIN tile_bounds bounds
                WHERE p_z BETWEEN 2 AND 3
                  AND edge.min_zoom <= p_z
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

                UNION ALL

                SELECT edge.edge_id AS id, edge.road_type, edge.geom
                FROM public.road_edge_lod_4 edge
                CROSS JOIN tile_bounds bounds
                WHERE p_z BETWEEN 4 AND 5
                  AND edge.min_zoom <= p_z
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

                UNION ALL

                SELECT edge.edge_id AS id, edge.road_type, edge.geom
                FROM public.road_edge_lod_6 edge
                CROSS JOIN tile_bounds bounds
                WHERE p_z BETWEEN 6 AND 7
                  AND edge.min_zoom <= p_z
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)
            ),
            tile_feature AS (
                SELECT
                    source.id,
                    source.road_type,
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
            SELECT COALESCE(ST_AsMVT(valid_feature, 'road', 4096, 'geom'), ''::BYTEA)
            FROM valid_feature
        )
        WHEN p_z >= 8 THEN (
            WITH source_feature AS (
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
                WHERE edge.status = 'active'
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
            SELECT COALESCE(ST_AsMVT(valid_feature, 'road', 4096, 'geom'), ''::BYTEA)
            FROM valid_feature
        )
        ELSE ''::BYTEA
    END;
$$;

SELECT public.refresh_road_edge_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_road_edge_lod() IS
    'Builds pixel-scale low-zoom road aggregates with merged line parts';
COMMENT ON FUNCTION public.get_local_road_vector_tile(INTEGER, INTEGER, INTEGER) IS
    'Returns paint-only properties at low zoom and detailed road properties at z8+';

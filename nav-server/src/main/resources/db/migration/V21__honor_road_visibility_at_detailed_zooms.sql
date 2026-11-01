-- The original detailed branch switched to every raw road at z8. Keep using
-- raw geometry there, but continue honoring the class visibility threshold so
-- the transition from offline LOD to detailed geometry is not a density jump.
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
                  AND public.road_render_min_zoom(edge.road_type) <= p_z
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

SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.get_local_road_vector_tile(INTEGER, INTEGER, INTEGER) IS
    'Returns class-filtered LOD roads at z2-z7 and class-filtered raw roads at z8+';

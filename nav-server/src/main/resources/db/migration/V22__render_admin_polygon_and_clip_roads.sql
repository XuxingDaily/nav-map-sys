-- Render the Guangzhou administrative area as a polygon base mask and clip
-- every road geometry to that mask before encoding it into an MVT tile.

ALTER FUNCTION public.refresh_base_map_lod()
    RENAME TO refresh_base_surface_lod;

CREATE OR REPLACE FUNCTION public.refresh_base_map_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    -- Retain the proven surface aggregation pipeline from V20.
    PERFORM public.refresh_base_surface_lod();

    -- Replace its temporary line boundaries with topology-preserving polygons.
    DELETE FROM public.base_map_lod WHERE theme = 'boundary';

    INSERT INTO public.base_map_lod (
        lod, theme, render_id, source_feature_id, class, name, min_zoom, geom
    )
    SELECT
        level.lod,
        'boundary',
        ROW_NUMBER() OVER (
            PARTITION BY level.lod
            ORDER BY area.adcode
        )::BIGINT,
        area.adcode::TEXT,
        area.level,
        area.name,
        2,
        ST_SimplifyPreserveTopology(area.geom_local, level.tolerance_m)
    FROM public.admin_area area
    CROSS JOIN (VALUES
        (2::SMALLINT, 2048.0::DOUBLE PRECISION),
        (4::SMALLINT, 512.0::DOUBLE PRECISION),
        (6::SMALLINT, 128.0::DOUBLE PRECISION),
        (8::SMALLINT, 32.0::DOUBLE PRECISION),
        (10::SMALLINT, 8.0::DOUBLE PRECISION),
        (12::SMALLINT, 2.0::DOUBLE PRECISION)
    ) AS level(lod, tolerance_m);

    ANALYZE public.base_map_lod;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_local_basemap_vector_tile(
    p_theme VARCHAR,
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
    selected_lod AS (
        SELECT CASE
            WHEN p_z BETWEEN 2 AND 3 THEN 2
            WHEN p_z BETWEEN 4 AND 5 THEN 4
            WHEN p_z BETWEEN 6 AND 7 THEN 6
            WHEN p_z BETWEEN 8 AND 9 THEN 8
            WHEN p_z BETWEEN 10 AND 11 THEN 10
            WHEN p_z BETWEEN 12 AND 13 THEN 12
            ELSE NULL
        END::SMALLINT AS lod
    ),
    source_feature AS (
        SELECT
            feature.render_id AS id,
            feature.source_feature_id,
            feature.class,
            feature.name,
            feature.geom
        FROM public.base_map_lod feature
        CROSS JOIN selected_lod selected
        CROSS JOIN tile_bounds bounds
        WHERE selected.lod IS NOT NULL
          AND feature.lod = selected.lod
          AND feature.theme = p_theme
          AND feature.min_zoom <= p_z
          AND feature.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

        UNION ALL

        SELECT
            feature.id,
            feature.source_feature_id,
            feature.class,
            feature.name,
            feature.geom_local
        FROM public.base_surface_feature feature
        CROSS JOIN tile_bounds bounds
        WHERE p_z >= 14
          AND feature.theme = p_theme
          AND p_z BETWEEN feature.min_zoom AND feature.max_zoom
          AND feature.geom_local && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

        UNION ALL

        SELECT
            area.adcode::BIGINT,
            area.adcode::TEXT,
            area.level,
            area.name,
            area.geom_local
        FROM public.admin_area area
        CROSS JOIN tile_bounds bounds
        WHERE p_theme = 'boundary'
          AND p_z >= 14
          AND area.geom_local && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)
    ),
    tile_feature AS (
        SELECT
            source.id,
            source.source_feature_id,
            source.class,
            source.name,
            ST_AsMVTGeom(source.geom, bounds.geom, 4096, 64, true) AS geom
        FROM source_feature source
        CROSS JOIN tile_bounds bounds
    ),
    valid_feature AS (
        SELECT * FROM tile_feature
        WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
    )
    SELECT COALESCE(
        ST_AsMVT(valid_feature, p_theme, 4096, 'geom'),
        ''::BYTEA
    )
    FROM valid_feature;
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
    ),
    admin_mask AS MATERIALIZED (
        SELECT ST_UnaryUnion(ST_Collect(geom_local)) AS geom
        FROM public.admin_area
    )
    SELECT CASE
        WHEN p_z BETWEEN 2 AND 7 THEN (
            WITH source_feature AS (
                SELECT edge.edge_id AS id, edge.road_type,
                       ST_CollectionExtract(ST_Intersection(edge.geom, mask.geom), 2) AS geom
                FROM public.road_edge_lod_2 edge
                CROSS JOIN tile_bounds bounds
                CROSS JOIN admin_mask mask
                WHERE p_z BETWEEN 2 AND 3
                  AND edge.min_zoom <= p_z
                  AND edge.geom && mask.geom
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

                UNION ALL

                SELECT edge.edge_id AS id, edge.road_type,
                       ST_CollectionExtract(ST_Intersection(edge.geom, mask.geom), 2) AS geom
                FROM public.road_edge_lod_4 edge
                CROSS JOIN tile_bounds bounds
                CROSS JOIN admin_mask mask
                WHERE p_z BETWEEN 4 AND 5
                  AND edge.min_zoom <= p_z
                  AND edge.geom && mask.geom
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)

                UNION ALL

                SELECT edge.edge_id AS id, edge.road_type,
                       ST_CollectionExtract(ST_Intersection(edge.geom, mask.geom), 2) AS geom
                FROM public.road_edge_lod_6 edge
                CROSS JOIN tile_bounds bounds
                CROSS JOIN admin_mask mask
                WHERE p_z BETWEEN 6 AND 7
                  AND edge.min_zoom <= p_z
                  AND edge.geom && mask.geom
                  AND edge.geom && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)
            ),
            tile_feature AS (
                SELECT source.id, source.road_type,
                       ST_AsMVTGeom(source.geom, bounds.geom, 4096, 64, true) AS geom
                FROM source_feature source
                CROSS JOIN tile_bounds bounds
                WHERE NOT ST_IsEmpty(source.geom)
            ),
            valid_feature AS (
                SELECT * FROM tile_feature
                WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
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
                    ST_CollectionExtract(ST_Intersection(edge.geom_local, mask.geom), 2) AS geom
                FROM public.road_edge edge
                CROSS JOIN tile_bounds bounds
                CROSS JOIN admin_mask mask
                WHERE edge.status = 'active'
                  AND public.road_render_min_zoom(edge.road_type) <= p_z
                  AND edge.geom_local && mask.geom
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
                WHERE NOT ST_IsEmpty(source.geom)
            ),
            valid_feature AS (
                SELECT * FROM tile_feature
                WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
            )
            SELECT COALESCE(ST_AsMVT(valid_feature, 'road', 4096, 'geom'), ''::BYTEA)
            FROM valid_feature
        )
        ELSE ''::BYTEA
    END;
$$;

CREATE OR REPLACE FUNCTION public.get_local_base_vector_tile(
    p_z INTEGER,
    p_x INTEGER,
    p_y INTEGER
)
RETURNS BYTEA
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    WITH layer_tile(draw_order, tile) AS (
        VALUES
            (5, public.get_local_basemap_vector_tile('boundary', p_z, p_x, p_y)),
            (10, public.get_local_basemap_vector_tile('landcover', p_z, p_x, p_y)),
            (20, public.get_local_basemap_vector_tile('water', p_z, p_x, p_y)),
            (30, public.get_local_basemap_vector_tile('building', p_z, p_x, p_y)),
            (35, public.get_local_basemap_vector_tile('parking', p_z, p_x, p_y)),
            (50, public.get_local_road_vector_tile(p_z, p_x, p_y))
    )
    SELECT COALESCE(
        string_agg(tile, ''::BYTEA ORDER BY draw_order)
            FILTER (WHERE octet_length(tile) > 0),
        ''::BYTEA
    )
    FROM layer_tile;
$$;

SELECT public.refresh_base_map_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_base_map_lod() IS
    'Refreshes shared surface LODs and replaces administrative lines with polygon LODs';
COMMENT ON FUNCTION public.get_local_road_vector_tile(INTEGER, INTEGER, INTEGER) IS
    'Returns class-filtered roads clipped to the merged Guangzhou administrative polygon';

-- Low-zoom road aggregates are stable offline data. Clip them to Guangzhou
-- once during refresh instead of intersecting large merged lines on every tile
-- request.

ALTER FUNCTION public.refresh_road_edge_lod()
    RENAME TO refresh_road_edge_lod_unclipped;

CREATE OR REPLACE FUNCTION public.refresh_road_edge_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM public.refresh_road_edge_lod_unclipped();

    WITH admin_mask AS (
        SELECT ST_UnaryUnion(ST_Collect(geom_local)) AS geom
        FROM public.admin_area
    )
    DELETE FROM public.road_edge_lod edge
    USING admin_mask mask
    WHERE NOT ST_Intersects(edge.geom, mask.geom)
       OR ST_IsEmpty(ST_CollectionExtract(ST_Intersection(edge.geom, mask.geom), 2));

    WITH admin_mask AS (
        SELECT ST_UnaryUnion(ST_Collect(geom_local)) AS geom
        FROM public.admin_area
    )
    UPDATE public.road_edge_lod edge
    SET geom = ST_CollectionExtract(ST_Intersection(edge.geom, mask.geom), 2)
    FROM admin_mask mask
    WHERE NOT ST_CoveredBy(edge.geom, mask.geom);

    ANALYZE public.road_edge_lod;
END;
$$;

ALTER FUNCTION public.get_local_road_vector_tile(INTEGER, INTEGER, INTEGER)
    RENAME TO get_local_raw_road_vector_tile;

CREATE OR REPLACE FUNCTION public.get_local_road_lod_vector_tile(
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
        SELECT * FROM tile_feature
        WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
    )
    SELECT COALESCE(ST_AsMVT(valid_feature, 'road', 4096, 'geom'), ''::BYTEA)
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
    SELECT CASE
        WHEN p_z BETWEEN 2 AND 7
            THEN public.get_local_road_lod_vector_tile(p_z, p_x, p_y)
        WHEN p_z >= 8
            THEN public.get_local_raw_road_vector_tile(p_z, p_x, p_y)
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
            (40, public.get_local_admin_outline_vector_tile(p_z, p_x, p_y)),
            (50, public.get_local_road_vector_tile(p_z, p_x, p_y))
    )
    SELECT COALESCE(
        string_agg(tile, ''::BYTEA ORDER BY draw_order)
            FILTER (WHERE octet_length(tile) > 0),
        ''::BYTEA
    )
    FROM layer_tile;
$$;

SELECT public.refresh_road_edge_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_road_edge_lod() IS
    'Builds road LODs and preclips them once to the exact Guangzhou polygon';

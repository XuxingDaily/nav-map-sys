-- Polygon rendering must be geometry-stable across zoom levels. Read the
-- authoritative polygons directly at every zoom: no simplification, union,
-- aggregation, or z14 source switch.

CREATE OR REPLACE FUNCTION public.refresh_base_map_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    TRUNCATE TABLE public.base_map_lod;

    -- Kept only for compatibility and inspection. Tile rendering reads the
    -- authoritative tables directly; every stored admin copy is also exact.
    INSERT INTO public.base_map_lod (
        lod, theme, render_id, source_feature_id, class, name, min_zoom, geom
    )
    SELECT
        level.lod,
        'boundary',
        ROW_NUMBER() OVER (PARTITION BY level.lod ORDER BY area.adcode)::BIGINT,
        area.adcode::TEXT,
        area.level,
        area.name,
        2,
        area.geom_local
    FROM public.admin_area area
    CROSS JOIN (VALUES
        (2::SMALLINT), (4::SMALLINT), (6::SMALLINT),
        (8::SMALLINT), (10::SMALLINT), (12::SMALLINT)
    ) AS level(lod);

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
    source_feature AS (
        SELECT
            feature.id,
            feature.source_feature_id,
            feature.class,
            feature.name,
            feature.geom_local AS geom
        FROM public.base_surface_feature feature
        CROSS JOIN tile_bounds bounds
        WHERE p_theme IN ('landcover', 'water', 'building', 'parking')
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
          AND p_z >= 2
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
    SELECT COALESCE(ST_AsMVT(valid_feature, p_theme, 4096, 'geom'), ''::BYTEA)
    FROM valid_feature;
$$;

CREATE OR REPLACE FUNCTION public.get_local_admin_outline_vector_tile(
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
    tile_feature AS (
        SELECT
            area.adcode::BIGINT AS id,
            area.name,
            ST_AsMVTGeom(
                ST_Boundary(area.geom_local),
                bounds.geom,
                4096,
                64,
                true
            ) AS geom
        FROM public.admin_area area
        CROSS JOIN tile_bounds bounds
        WHERE p_z >= 2
          AND area.geom_local && ST_Expand(bounds.geom, bounds.tile_size * 64.0 / 4096.0)
    ),
    valid_feature AS (
        SELECT * FROM tile_feature
        WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
    )
    SELECT COALESCE(
        ST_AsMVT(valid_feature, 'boundary_outline', 4096, 'geom'),
        ''::BYTEA
    )
    FROM valid_feature;
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

SELECT public.refresh_base_map_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.get_local_basemap_vector_tile(VARCHAR, INTEGER, INTEGER, INTEGER) IS
    'Returns authoritative unsimplified polygon geometry at every zoom';

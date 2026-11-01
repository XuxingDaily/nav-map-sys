-- Repair the first basemap LOD thresholds and keep district rings closed.
-- V19 simplified boundary lines after extracting them from polygons, which can
-- collapse individual ring segments at low zoom. Simplifying polygons first
-- preserves their topology; the boundary is extracted only afterwards.

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
        WHEN 'primary' THEN 5
        WHEN 'primary_link' THEN 5
        WHEN 'secondary' THEN 7
        WHEN 'secondary_link' THEN 7
        WHEN 'tertiary' THEN 9
        WHEN 'tertiary_link' THEN 9
        WHEN 'residential' THEN 11
        WHEN 'unclassified' THEN 11
        WHEN 'road' THEN 11
        WHEN 'service' THEN 13
        WHEN 'living_street' THEN 13
        ELSE 14
    END::SMALLINT;
$$;

UPDATE public.base_surface_feature
SET min_zoom = CASE theme
    WHEN 'water' THEN CASE
        WHEN area_m2 >= 10000000.0 THEN 2
        WHEN area_m2 >= 1000000.0 THEN 4
        WHEN area_m2 >= 100000.0 THEN 6
        WHEN area_m2 >= 10000.0 THEN 8
        ELSE 10
    END
    WHEN 'landcover' THEN GREATEST(
        CASE class
            WHEN 'forest' THEN 3
            WHEN 'residential' THEN 3
            WHEN 'industrial' THEN 4
            WHEN 'park' THEN 5
            WHEN 'commercial' THEN 5
            WHEN 'retail' THEN 5
            WHEN 'military' THEN 5
            WHEN 'grass' THEN 6
            WHEN 'farmland' THEN 6
            WHEN 'meadow' THEN 6
            WHEN 'orchard' THEN 6
            WHEN 'scrub' THEN 6
            WHEN 'recreation_ground' THEN 6
            ELSE 8
        END,
        CASE
            WHEN area_m2 >= 10000000.0 THEN 3
            WHEN area_m2 >= 1000000.0 THEN 4
            WHEN area_m2 >= 100000.0 THEN 6
            WHEN area_m2 >= 10000.0 THEN 8
            ELSE 10
        END
    )
    WHEN 'building' THEN CASE
        WHEN area_m2 >= 50000.0 THEN 11
        WHEN area_m2 >= 10000.0 THEN 12
        WHEN area_m2 >= 1000.0 THEN 13
        ELSE 14
    END
    WHEN 'parking' THEN CASE
        WHEN area_m2 >= 50000.0 THEN 13
        WHEN area_m2 >= 10000.0 THEN 14
        ELSE 15
    END
    ELSE min_zoom
END;

UPDATE public.map_layer
SET min_zoom = CASE code
        WHEN 'water' THEN 2
        WHEN 'landcover' THEN 3
        WHEN 'building' THEN 11
        WHEN 'parking' THEN 13
        ELSE min_zoom
    END,
    updated_at = now()
WHERE code IN ('water', 'landcover', 'building', 'parking');

CREATE OR REPLACE FUNCTION public.refresh_base_map_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    TRUNCATE TABLE public.base_map_lod;

    WITH levels AS (
        SELECT *
        FROM (VALUES
            (2::SMALLINT, 3::SMALLINT, 8192.0::DOUBLE PRECISION, 32768.0::DOUBLE PRECISION),
            (4::SMALLINT, 5::SMALLINT, 2048.0::DOUBLE PRECISION, 8192.0::DOUBLE PRECISION),
            (6::SMALLINT, 7::SMALLINT, 512.0::DOUBLE PRECISION, 2048.0::DOUBLE PRECISION),
            (8::SMALLINT, 9::SMALLINT, 128.0::DOUBLE PRECISION, 512.0::DOUBLE PRECISION),
            (10::SMALLINT, 11::SMALLINT, 32.0::DOUBLE PRECISION, 128.0::DOUBLE PRECISION),
            (12::SMALLINT, 13::SMALLINT, 8.0::DOUBLE PRECISION, 32.0::DOUBLE PRECISION)
        ) AS value(lod, maximum_zoom, tolerance_m, cell_size_m)
    ),
    generalized AS (
        SELECT
            level.lod,
            feature.theme,
            feature.class,
            feature.min_zoom,
            level.tolerance_m,
            level.cell_size_m,
            ST_SimplifyPreserveTopology(feature.geom_local, level.tolerance_m) AS geom
        FROM public.base_surface_feature feature
        CROSS JOIN levels level
        WHERE feature.min_zoom <= level.maximum_zoom
          AND feature.max_zoom >= level.lod
    ),
    located AS (
        SELECT
            *,
            FLOOR((ST_X(ST_PointOnSurface(geom)) - 12550000.0) / cell_size_m)::INTEGER AS cell_x,
            FLOOR((2800000.0 - ST_Y(ST_PointOnSurface(geom))) / cell_size_m)::INTEGER AS cell_y
        FROM generalized
        WHERE NOT ST_IsEmpty(geom)
    ),
    aggregated AS (
        SELECT
            lod,
            theme,
            class,
            min_zoom,
            cell_x,
            cell_y,
            ST_CollectionExtract(
                ST_MakeValid(ST_UnaryUnion(ST_Collect(geom))),
                3
            )::geometry(Geometry, 3857) AS geom
        FROM located
        GROUP BY lod, theme, class, min_zoom, cell_x, cell_y
    ),
    numbered AS (
        SELECT
            lod,
            theme,
            ROW_NUMBER() OVER (
                PARTITION BY lod, theme
                ORDER BY cell_y, cell_x, min_zoom, class
            )::BIGINT AS render_id,
            class,
            min_zoom,
            geom
        FROM aggregated
        WHERE NOT ST_IsEmpty(geom)
    )
    INSERT INTO public.base_map_lod (
        lod, theme, render_id, source_feature_id, class, name, min_zoom, geom
    )
    SELECT lod, theme, render_id, NULL, class, NULL, min_zoom, geom
    FROM numbered;

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
        ST_Boundary(
            ST_SimplifyPreserveTopology(area.geom_local, level.tolerance_m)
        )
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

SELECT public.refresh_road_edge_lod();
SELECT public.refresh_base_map_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_base_map_lod() IS
    'Builds shared polygon LODs and topology-preserving closed district boundaries';

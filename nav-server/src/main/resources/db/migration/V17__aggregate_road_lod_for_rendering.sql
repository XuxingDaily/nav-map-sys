-- Low-zoom rendering only needs road_type styling. Aggregate simplified road
-- fragments by a local render cell and road type so the browser decodes and
-- paints tens of features per tile instead of tens of thousands.
ALTER TABLE public.road_edge_lod
    ALTER COLUMN geom TYPE geometry(Geometry, 3857)
    USING geom::geometry(Geometry, 3857);

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
            level.cell_size_m,
            COALESCE(edge.road_type, 'road') AS road_type,
            ST_SimplifyPreserveTopology(
                edge.geom_local,
                level.tolerance_m
            ) AS geom
        FROM public.road_edge edge
        CROSS JOIN (VALUES
            -- Aggregate at the highest zoom represented by each LOD bucket.
            (2::SMALLINT, 3::SMALLINT, 64.0::DOUBLE PRECISION, 32768.0::DOUBLE PRECISION),
            (4::SMALLINT, 5::SMALLINT, 16.0::DOUBLE PRECISION, 8192.0::DOUBLE PRECISION),
            (6::SMALLINT, 7::SMALLINT, 4.0::DOUBLE PRECISION, 2048.0::DOUBLE PRECISION)
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
            ST_CollectionExtract(ST_Collect(geom), 2)::geometry(Geometry, 3857) AS geom
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
        lod,
        render_edge_id,
        min_zoom,
        NULL,
        road_type,
        road_type,
        NULL,
        'MIXED',
        'MIXED',
        NULL,
        NULL,
        geom
    FROM numbered;

    ANALYZE public.road_edge_lod;
END;
$$;

SELECT public.refresh_road_edge_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_road_edge_lod() IS
    'Builds low-zoom display-only road aggregates by render cell and road type';

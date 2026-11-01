-- osm2pgrouting splits an OSM way at graph vertices. The first LOD version
-- simplified each fragment but kept it as a separate MVT feature. Rebuild the
-- derived display data by merging compatible fragments from the same OSM way.
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
            CASE
                WHEN edge.osm_id IS NOT NULL THEN 'osm:' || edge.osm_id::TEXT
                ELSE 'edge:' || edge.id::TEXT
            END AS render_group_key,
            edge.id AS source_edge_id,
            edge.osm_id,
            edge.name,
            edge.road_type,
            edge.oneway_raw,
            edge.driving_direction,
            edge.walking_direction,
            edge.maxspeed_forward,
            edge.maxspeed_backward,
            ST_SimplifyPreserveTopology(
                edge.geom_local,
                level.tolerance_m
            ) AS geom
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
          AND visibility.min_zoom <= level.maximum_zoom
    ),
    merged AS (
        SELECT
            lod,
            min_zoom,
            render_group_key,
            MIN(source_edge_id) AS source_edge_id,
            osm_id,
            name,
            road_type,
            oneway_raw,
            driving_direction,
            walking_direction,
            maxspeed_forward,
            maxspeed_backward,
            ST_LineMerge(ST_UnaryUnion(ST_Collect(geom))) AS geom
        FROM generalized
        GROUP BY
            lod,
            min_zoom,
            render_group_key,
            osm_id,
            name,
            road_type,
            oneway_raw,
            driving_direction,
            walking_direction,
            maxspeed_forward,
            maxspeed_backward
    ),
    parts AS (
        SELECT
            merged.*,
            dumped.path AS part_path,
            dumped.geom::geometry(LineString, 3857) AS part_geom
        FROM merged
        CROSS JOIN LATERAL ST_Dump(ST_CollectionExtract(merged.geom, 2)) dumped
        WHERE NOT ST_IsEmpty(dumped.geom)
    ),
    numbered AS (
        SELECT
            ROW_NUMBER() OVER (
                PARTITION BY lod
                ORDER BY render_group_key, source_edge_id, part_path
            )::BIGINT AS render_edge_id,
            *
        FROM parts
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
        osm_id,
        name,
        road_type,
        oneway_raw,
        driving_direction,
        walking_direction,
        maxspeed_forward,
        maxspeed_backward,
        part_geom
    FROM numbered;

    ANALYZE public.road_edge_lod;
END;
$$;

SELECT public.refresh_road_edge_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_road_edge_lod() IS
    'Rebuilds low-zoom road rendering data and merges compatible fragments from each OSM way';

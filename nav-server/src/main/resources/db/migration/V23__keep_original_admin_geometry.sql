-- Guangzhou has only eleven administrative polygons. Reuse their original
-- geometry at every render zoom instead of maintaining simplified variants.
CREATE OR REPLACE FUNCTION public.refresh_base_map_lod()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM public.refresh_base_surface_lod();

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
        area.geom_local
    FROM public.admin_area area
    CROSS JOIN (VALUES
        (2::SMALLINT),
        (4::SMALLINT),
        (6::SMALLINT),
        (8::SMALLINT),
        (10::SMALLINT),
        (12::SMALLINT)
    ) AS level(lod);

    ANALYZE public.base_map_lod;
END;
$$;

SELECT public.refresh_base_map_lod();
SELECT public.bump_dataset_revision('map');

COMMENT ON FUNCTION public.refresh_base_map_lod() IS
    'Refreshes surface LODs and reuses unsimplified administrative polygons at every zoom';

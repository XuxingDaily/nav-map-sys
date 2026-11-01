CREATE OR REPLACE FUNCTION public.local_tile_envelope(
    p_z INTEGER,
    p_x INTEGER,
    p_y INTEGER
)
RETURNS geometry(Polygon, 3857)
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    tile_count BIGINT;
    tile_size  DOUBLE PRECISION;
    min_x DOUBLE PRECISION;
    min_y DOUBLE PRECISION;
    max_x DOUBLE PRECISION;
    max_y DOUBLE PRECISION;
BEGIN
    IF p_z < 0 OR p_z > 22 THEN
        RAISE EXCEPTION 'Invalid local tile zoom: %', p_z;
    END IF;

    tile_count := 1::BIGINT << p_z;

    IF p_x < 0 OR p_x >= tile_count
       OR p_y < 0 OR p_y >= tile_count THEN
        RAISE EXCEPTION
            'Invalid local tile coordinate: z=%, x=%, y=%',
            p_z, p_x, p_y;
    END IF;

    tile_size := 262144.0 / tile_count;
    min_x := 12550000.0 + p_x * tile_size;
    max_x := 12550000.0 + (p_x + 1) * tile_size;
    max_y := 2800000.0 - p_y * tile_size;
    min_y := 2800000.0 - (p_y + 1) * tile_size;

    RETURN ST_MakeEnvelope(min_x, min_y, max_x, max_y, 3857);
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
    ),
    tile_feature AS (
        SELECT
            e.id,
            e.osm_id,
            e.name,
            e.road_type,
            e.oneway_raw,
            e.driving_direction,
            e.walking_direction,
            e.maxspeed_forward,
            e.maxspeed_backward,
            ST_AsMVTGeom(e.geom_local, b.geom, 4096, 64, true) AS geom
        FROM public.road_edge e
        CROSS JOIN tile_bounds b
        WHERE e.status = 'active'
          AND e.geom_local && ST_Expand(
              b.geom,
              b.tile_size * 64.0 / 4096.0
          )
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

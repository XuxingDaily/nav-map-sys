-- Preserve source WGS84 geometry and derive the Web Mercator geometry used by
-- map rendering, spatial indexes and routing queries. Existing demo geometry
-- has no declared CRS; treating its numeric values as EPSG:3857 keeps the
-- legacy coordinates stable until the Guangzhou import replaces the rows.

ALTER TABLE public.road_node
    ADD COLUMN osm_id BIGINT,
    ADD COLUMN geom geometry(Point, 4326);

UPDATE public.road_node
SET geom = ST_Transform(ST_SetSRID(geom_local, 3857), 4326);

ALTER TABLE public.road_node
    ALTER COLUMN geom SET NOT NULL,
    DROP CONSTRAINT road_node_geom_local_valid_ck;

DROP INDEX public.road_node_geom_local_gix;

ALTER TABLE public.road_node
    DROP COLUMN geom_local,
    ADD COLUMN geom_local geometry(Point, 3857)
        GENERATED ALWAYS AS (ST_Transform(geom, 3857)) STORED;

CREATE INDEX road_node_geom_gix
    ON public.road_node USING GIST (geom);
CREATE INDEX road_node_geom_local_gix
    ON public.road_node USING GIST (geom_local);
CREATE UNIQUE INDEX road_node_osm_id_uidx
    ON public.road_node (osm_id)
    WHERE osm_id IS NOT NULL;

ALTER TABLE public.road_edge
    ADD COLUMN osm_id BIGINT,
    ADD COLUMN tag_id INTEGER,
    ADD COLUMN source_osm BIGINT,
    ADD COLUMN target_osm BIGINT,
    ADD COLUMN routing_cost DOUBLE PRECISION,
    ADD COLUMN routing_reverse_cost DOUBLE PRECISION,
    ADD COLUMN rule TEXT,
    ADD COLUMN oneway_code SMALLINT,
    ADD COLUMN oneway_raw VARCHAR(20),
    ADD COLUMN maxspeed_forward DOUBLE PRECISION,
    ADD COLUMN maxspeed_backward DOUBLE PRECISION,
    ADD COLUMN priority DOUBLE PRECISION,
    ADD COLUMN geom geometry(LineString, 4326);

UPDATE public.road_edge
SET geom = ST_Transform(ST_SetSRID(geom_local, 3857), 4326),
    oneway_code = CASE
        WHEN one_way IS TRUE THEN 1
        WHEN one_way IS FALSE THEN 2
        ELSE 0
    END,
    oneway_raw = CASE
        WHEN one_way IS TRUE THEN 'YES'
        WHEN one_way IS FALSE THEN 'NO'
        ELSE 'UNKNOWN'
    END;

ALTER TABLE public.road_edge
    ALTER COLUMN geom SET NOT NULL,
    DROP CONSTRAINT road_edge_geom_local_valid_ck;

DROP INDEX public.road_edge_geom_local_gix;

ALTER TABLE public.road_edge
    DROP COLUMN geom_local,
    DROP COLUMN one_way,
    ADD COLUMN geom_local geometry(LineString, 3857)
        GENERATED ALWAYS AS (ST_Transform(geom, 3857)) STORED,
    ADD COLUMN driving_direction VARCHAR(10)
        GENERATED ALWAYS AS (
            CASE
                WHEN driving_cost_s > 0 AND driving_reverse_s > 0 THEN 'BOTH'
                WHEN driving_cost_s > 0 THEN 'FORWARD'
                WHEN driving_reverse_s > 0 THEN 'REVERSE'
                ELSE 'BLOCKED'
            END
        ) STORED,
    ADD COLUMN walking_direction VARCHAR(10)
        GENERATED ALWAYS AS (
            CASE
                WHEN walking_cost_s > 0 AND walking_reverse_s > 0 THEN 'BOTH'
                WHEN walking_cost_s > 0 THEN 'FORWARD'
                WHEN walking_reverse_s > 0 THEN 'REVERSE'
                ELSE 'BLOCKED'
            END
        ) STORED;

ALTER TABLE public.road_edge
    ADD CONSTRAINT road_edge_oneway_code_ck
        CHECK (oneway_code IS NULL OR oneway_code IN (-1, 0, 1, 2, 3)),
    ADD CONSTRAINT road_edge_driving_direction_ck
        CHECK (driving_direction IN ('BOTH', 'FORWARD', 'REVERSE', 'BLOCKED')),
    ADD CONSTRAINT road_edge_walking_direction_ck
        CHECK (walking_direction IN ('BOTH', 'FORWARD', 'REVERSE', 'BLOCKED'));

CREATE INDEX road_edge_geom_gix
    ON public.road_edge USING GIST (geom);
CREATE INDEX road_edge_geom_local_gix
    ON public.road_edge USING GIST (geom_local);
CREATE INDEX road_edge_road_type_idx
    ON public.road_edge (road_type);
CREATE INDEX road_edge_driving_direction_idx
    ON public.road_edge (driving_direction);

ALTER TABLE public.poi
    ADD COLUMN source_id UUID,
    ADD COLUMN category VARCHAR(100),
    ADD COLUMN basic_category VARCHAR(100),
    ADD COLUMN confidence DOUBLE PRECISION,
    ADD COLUMN address TEXT,
    ADD COLUMN geom geometry(Point, 4326);

UPDATE public.poi
SET source_id = md5('legacy-poi:' || id::TEXT)::UUID,
    geom = ST_Transform(ST_SetSRID(geom_local, 3857), 4326);

ALTER TABLE public.poi
    ALTER COLUMN source_id SET NOT NULL,
    ALTER COLUMN geom SET NOT NULL,
    DROP CONSTRAINT poi_geom_local_valid_ck,
    ADD CONSTRAINT poi_confidence_ck
        CHECK (confidence IS NULL OR confidence BETWEEN 0.0 AND 1.0);

DROP INDEX public.poi_geom_local_gix;

ALTER TABLE public.poi
    DROP COLUMN geom_local,
    ADD COLUMN geom_local geometry(Point, 3857)
        GENERATED ALWAYS AS (ST_Transform(geom, 3857)) STORED;

CREATE UNIQUE INDEX poi_source_id_uidx
    ON public.poi (source_id);
CREATE INDEX poi_geom_gix
    ON public.poi USING GIST (geom);
CREATE INDEX poi_geom_local_gix
    ON public.poi USING GIST (geom_local);
CREATE INDEX poi_category_idx
    ON public.poi (category);
CREATE INDEX poi_basic_category_idx
    ON public.poi (basic_category);

UPDATE public.road_edge
SET coordinate_status = 'projected_3857';

UPDATE public.poi
SET coordinate_status = 'projected_3857';

COMMENT ON COLUMN public.road_edge.geom IS
    'Source WGS84 road geometry (EPSG:4326)';
COMMENT ON COLUMN public.road_edge.geom_local IS
    'Generated Web Mercator road geometry used by map and routing queries (EPSG:3857)';
COMMENT ON COLUMN public.road_edge.oneway_raw IS
    'Original osm2pgrouting direction label; directional costs remain authoritative for routing';
COMMENT ON COLUMN public.poi.geom IS
    'Source WGS84 POI geometry (EPSG:4326)';
COMMENT ON COLUMN public.poi.geom_local IS
    'Generated Web Mercator POI geometry used by map and spatial queries (EPSG:3857)';

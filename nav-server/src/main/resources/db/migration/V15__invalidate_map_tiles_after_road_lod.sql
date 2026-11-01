-- V14 changes the bytes produced by the road MVT function. Advance the map
-- dataset revision so cache-aside keys cannot reuse pre-LOD vector tiles.
SELECT public.bump_dataset_revision('map');

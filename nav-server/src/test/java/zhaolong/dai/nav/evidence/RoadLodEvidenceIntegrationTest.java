package zhaolong.dai.nav.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only, opt-in benchmark comparing raw roads with the offline road LODs.
 * Both branches encode the same attributes and use the same visibility rule,
 * so the reported difference is attributable to aggregation/simplification.
 */
@SpringBootTest(properties = "logging.level.zhaolong.dai.nav.map.mapper=OFF")
@EnabledIfSystemProperty(named = "lod.evidence", matches = "true")
class RoadLodEvidenceIntegrationTest {

    private static final double ORIGIN_X = 12_550_000.0;
    private static final double ORIGIN_Y = 2_800_000.0;
    private static final double WORLD_SPAN = 262_144.0;
    private static final int SAMPLE_TILES_PER_ZOOM = 3;
    private static final int WARMUPS = 2;
    private static final int ITERATIONS = 7;

    private static final String RAW_TILE_SQL = """
            WITH tile_bounds AS (
                SELECT public.local_tile_envelope(?, ?, ?) AS geom,
                       262144.0 / power(2, ?) AS tile_size
            ),
            admin_mask AS MATERIALIZED (
                SELECT ST_UnaryUnion(ST_Collect(geom_local)) AS geom
                FROM public.admin_area
            ),
            source_feature AS (
                SELECT edge.id, edge.road_type,
                       ST_CollectionExtract(
                           ST_Intersection(edge.geom_local, mask.geom), 2
                       ) AS geom
                FROM public.road_edge edge
                CROSS JOIN tile_bounds bounds
                CROSS JOIN admin_mask mask
                WHERE edge.status = 'active'
                  AND public.road_render_min_zoom(edge.road_type) <= ?
                  AND edge.geom_local && mask.geom
                  AND edge.geom_local && ST_Expand(
                      bounds.geom, bounds.tile_size * 64.0 / 4096.0
                  )
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
            SELECT COALESCE(octet_length(ST_AsMVT(valid_feature, 'road', 4096, 'geom')), 0),
                   count(*),
                   COALESCE(sum(ST_NPoints(geom)), 0)
            FROM valid_feature
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldQuantifyRoadLodAgainstComparableRawTiles() throws Exception {
        List<Double> allRawTimings = new ArrayList<>();
        List<Double> allLodTimings = new ArrayList<>();
        long totalRawBytes = 0;
        long totalLodBytes = 0;
        long totalRawFeatures = 0;
        long totalLodFeatures = 0;
        long totalRawVertices = 0;
        long totalLodVertices = 0;
        int measuredTiles = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            for (int zoom = 2; zoom <= 7; zoom++) {
                List<Tile> tiles = densestVisibleRoadTiles(connection, zoom);
                assertThat(tiles).as("representative tiles at z%d", zoom).isNotEmpty();

                List<Double> rawTimings = new ArrayList<>();
                List<Double> lodTimings = new ArrayList<>();
                long rawBytes = 0;
                long lodBytes = 0;
                long rawFeatures = 0;
                long lodFeatures = 0;
                long rawVertices = 0;
                long lodVertices = 0;

                for (Tile tile : tiles) {
                    String lodSql = lodTileSql(zoom);
                    for (int i = 0; i < WARMUPS; i++) {
                        executeRaw(connection, tile);
                        executeLod(connection, tile, lodSql);
                    }

                    TileMetrics raw = null;
                    TileMetrics lod = null;
                    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                        if ((iteration & 1) == 0) {
                            raw = executeRaw(connection, tile);
                            lod = executeLod(connection, tile, lodSql);
                        }
                        else {
                            lod = executeLod(connection, tile, lodSql);
                            raw = executeRaw(connection, tile);
                        }
                        rawTimings.add(raw.elapsedMs());
                        lodTimings.add(lod.elapsedMs());
                    }

                    assertThat(raw).isNotNull();
                    assertThat(lod).isNotNull();
                    assertThat(raw.features()).as("raw features for %s", tile).isPositive();
                    assertThat(lod.features()).as("LOD features for %s", tile).isPositive();

                    rawBytes += raw.bytes();
                    lodBytes += lod.bytes();
                    rawFeatures += raw.features();
                    lodFeatures += lod.features();
                    rawVertices += raw.vertices();
                    lodVertices += lod.vertices();
                }

                Collections.sort(rawTimings);
                Collections.sort(lodTimings);
                System.out.printf(Locale.ROOT,
                        "ROAD_LOD_EVIDENCE z=%d tiles=%d samplesPerBranch=%d "
                                + "rawP50Ms=%.3f rawP95Ms=%.3f lodP50Ms=%.3f lodP95Ms=%.3f "
                                + "speedupP50=%.2fx rawBytes=%d lodBytes=%d bytesReduction=%.1f%% "
                                + "rawFeatures=%d lodFeatures=%d featureReduction=%.1f%% "
                                + "rawVertices=%d lodVertices=%d vertexReduction=%.1f%%%n",
                        zoom,
                        tiles.size(),
                        rawTimings.size(),
                        percentile(rawTimings, 0.50),
                        percentile(rawTimings, 0.95),
                        percentile(lodTimings, 0.50),
                        percentile(lodTimings, 0.95),
                        percentile(rawTimings, 0.50) / percentile(lodTimings, 0.50),
                        rawBytes,
                        lodBytes,
                        reduction(rawBytes, lodBytes),
                        rawFeatures,
                        lodFeatures,
                        reduction(rawFeatures, lodFeatures),
                        rawVertices,
                        lodVertices,
                        reduction(rawVertices, lodVertices));

                allRawTimings.addAll(rawTimings);
                allLodTimings.addAll(lodTimings);
                totalRawBytes += rawBytes;
                totalLodBytes += lodBytes;
                totalRawFeatures += rawFeatures;
                totalLodFeatures += lodFeatures;
                totalRawVertices += rawVertices;
                totalLodVertices += lodVertices;
                measuredTiles += tiles.size();
            }
        }

        Collections.sort(allRawTimings);
        Collections.sort(allLodTimings);
        assertThat(totalLodBytes).isLessThan(totalRawBytes);
        assertThat(totalLodFeatures).isLessThan(totalRawFeatures);
        assertThat(totalLodVertices).isLessThan(totalRawVertices);
        System.out.printf(Locale.ROOT,
                "ROAD_LOD_SUMMARY zooms=2-7 tiles=%d samplesPerBranch=%d "
                        + "rawP50Ms=%.3f rawP95Ms=%.3f lodP50Ms=%.3f lodP95Ms=%.3f "
                        + "speedupP50=%.2fx rawBytes=%d lodBytes=%d bytesReduction=%.1f%% "
                        + "rawFeatures=%d lodFeatures=%d featureReduction=%.1f%% "
                        + "rawVertices=%d lodVertices=%d vertexReduction=%.1f%%%n",
                measuredTiles,
                allRawTimings.size(),
                percentile(allRawTimings, 0.50),
                percentile(allRawTimings, 0.95),
                percentile(allLodTimings, 0.50),
                percentile(allLodTimings, 0.95),
                percentile(allRawTimings, 0.50) / percentile(allLodTimings, 0.50),
                totalRawBytes,
                totalLodBytes,
                reduction(totalRawBytes, totalLodBytes),
                totalRawFeatures,
                totalLodFeatures,
                reduction(totalRawFeatures, totalLodFeatures),
                totalRawVertices,
                totalLodVertices,
                reduction(totalRawVertices, totalLodVertices));
    }

    private List<Tile> densestVisibleRoadTiles(Connection connection, int zoom) throws Exception {
        double tileSize = WORLD_SPAN / Math.pow(2.0, zoom);
        String sql = """
                WITH visible_road AS (
                    SELECT floor((ST_X(ST_LineInterpolatePoint(geom_local, 0.5)) - ?) / ?)::int AS x,
                           floor((? - ST_Y(ST_LineInterpolatePoint(geom_local, 0.5))) / ?)::int AS y
                    FROM public.road_edge road
                    WHERE status = 'active'
                      AND public.road_render_min_zoom(road_type) <= ?
                      AND EXISTS (
                          SELECT 1
                          FROM public.admin_area area
                          WHERE road.geom_local && area.geom_local
                            AND ST_Intersects(
                                ST_LineInterpolatePoint(road.geom_local, 0.5),
                                area.geom_local
                            )
                      )
                )
                SELECT x, y,
                       count(*) AS road_count
                FROM visible_road
                WHERE x BETWEEN 0 AND ?
                  AND y BETWEEN 0 AND ?
                GROUP BY x, y
                ORDER BY road_count DESC, x, y
                LIMIT ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int maxTile = (1 << zoom) - 1;
            statement.setDouble(1, ORIGIN_X);
            statement.setDouble(2, tileSize);
            statement.setDouble(3, ORIGIN_Y);
            statement.setDouble(4, tileSize);
            statement.setInt(5, zoom);
            statement.setInt(6, maxTile);
            statement.setInt(7, maxTile);
            statement.setInt(8, SAMPLE_TILES_PER_ZOOM);
            List<Tile> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new Tile(zoom, rows.getInt(1), rows.getInt(2)));
                }
            }
            return result;
        }
    }

    private TileMetrics executeRaw(Connection connection, Tile tile) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(RAW_TILE_SQL)) {
            statement.setInt(1, tile.z());
            statement.setInt(2, tile.x());
            statement.setInt(3, tile.y());
            statement.setInt(4, tile.z());
            statement.setInt(5, tile.z());
            return execute(statement);
        }
    }

    private TileMetrics executeLod(Connection connection, Tile tile, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, tile.z());
            statement.setInt(2, tile.x());
            statement.setInt(3, tile.y());
            statement.setInt(4, tile.z());
            statement.setInt(5, tile.z());
            return execute(statement);
        }
    }

    private TileMetrics execute(PreparedStatement statement) throws Exception {
        long started = System.nanoTime();
        try (ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            return new TileMetrics(
                    result.getLong(1), result.getLong(2), result.getLong(3), elapsedMs
            );
        }
    }

    private String lodTileSql(int zoom) {
        int lod = zoom <= 3 ? 2 : zoom <= 5 ? 4 : 6;
        return """
                WITH tile_bounds AS (
                    SELECT public.local_tile_envelope(?, ?, ?) AS geom,
                           262144.0 / power(2, ?) AS tile_size
                ),
                source_feature AS (
                    SELECT edge.edge_id AS id, edge.road_type, edge.geom
                    FROM public.road_edge_lod_%d edge
                    CROSS JOIN tile_bounds bounds
                    WHERE edge.min_zoom <= ?
                      AND edge.geom && ST_Expand(
                          bounds.geom, bounds.tile_size * 64.0 / 4096.0
                      )
                ),
                tile_feature AS (
                    SELECT source.id, source.road_type,
                           ST_AsMVTGeom(source.geom, bounds.geom, 4096, 64, true) AS geom
                    FROM source_feature source
                    CROSS JOIN tile_bounds bounds
                ),
                valid_feature AS (
                    SELECT * FROM tile_feature
                    WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom)
                )
                SELECT COALESCE(octet_length(ST_AsMVT(valid_feature, 'road', 4096, 'geom')), 0),
                       count(*),
                       COALESCE(sum(ST_NPoints(geom)), 0)
                FROM valid_feature
                """.formatted(lod);
    }

    private double percentile(List<Double> sorted, double percentile) {
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private double reduction(long raw, long lod) {
        return raw == 0 ? 0.0 : 100.0 * (raw - lod) / raw;
    }

    private record Tile(int z, int x, int y) {
    }

    private record TileMetrics(long bytes, long features, long vertices, double elapsedMs) {
    }
}

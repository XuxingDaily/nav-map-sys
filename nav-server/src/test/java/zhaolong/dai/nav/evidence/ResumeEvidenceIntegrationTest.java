package zhaolong.dai.nav.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import zhaolong.dai.nav.dto.LocalPointDTO;
import zhaolong.dai.nav.dto.routing.TourRequestDTO;
import zhaolong.dai.nav.dto.routing.WaypointDTO;
import zhaolong.dai.nav.enums.routing.RouteStrategy;
import zhaolong.dai.nav.enums.routing.TourAlgorithm;
import zhaolong.dai.nav.enums.routing.TravelMode;
import zhaolong.dai.nav.routing.domain.SnappedPoint;
import zhaolong.dai.nav.routing.mapper.RoutingMapper;
import zhaolong.dai.nav.routing.mapper.model.CostMatrixRow;
import zhaolong.dai.nav.routing.optimizer.HeldKarpTourOptimizer;
import zhaolong.dai.nav.routing.service.RoadSnapService;
import zhaolong.dai.nav.routing.service.TourPlanningService;
import zhaolong.dai.nav.vo.routing.TourPlanVO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in evidence run for resume claims. It uses the real PostGIS/pgRouting
 * database and is intentionally excluded from the normal unit-test path.
 *
 * Run with:
 * mvn -pl nav-server -am -Dtest=ResumeEvidenceIntegrationTest
 *     -Dresume.evidence=true -Dsurefire.failIfNoSpecifiedTests=false test
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "resume.evidence", matches = "true")
class ResumeEvidenceIntegrationTest {

    private static final Pattern EXECUTION_TIME =
            Pattern.compile("\\\"Execution Time\\\"\\s*:\\s*([0-9.]+)");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TourPlanningService tourPlanningService;

    @Autowired
    private RoadSnapService roadSnapService;

    @Autowired
    private RoutingMapper routingMapper;

    @Test
    void shouldCollectDatasetIndexAndTourEvidence() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long poiCount = scalarLong(connection,
                    "SELECT count(*) FROM public.poi WHERE status = 'active'");
            long edgeCount = scalarLong(connection,
                    "SELECT count(*) FROM public.road_edge WHERE status = 'active'");
            assertThat(poiCount).isPositive();
            assertThat(edgeCount).isPositive();

            double[] center = firstPoiCoordinate(connection);
            PlanEvidence indexed = measureSpatialQuery(connection, center, true);
            PlanEvidence sequential = measureSpatialQuery(connection, center, false);

            assertThat(indexed.plan()).containsAnyOf("Index Scan", "Bitmap Index Scan");
            assertThat(sequential.plan()).contains("Seq Scan");

            List<WaypointDTO> stops = roadWaypoints(connection, 10);
            TourRequestDTO request = TourRequestDTO.builder()
                    .stops(stops)
                    .algorithm(TourAlgorithm.AUTO)
                    .travelMode(TravelMode.WALKING)
                    .strategy(RouteStrategy.FASTEST)
                    .roundTrip(true)
                    .fixedStartIndex(0)
                    .maxSnapDistanceLocal(5.0)
                    .build();

            List<SnappedPoint> snapped = roadSnapService.snapAll(stops, TravelMode.WALKING, 5.0);
            long matrixStarted = System.nanoTime();
            List<CostMatrixRow> matrixRows = routingMapper.selectCostMatrix(
                    pointsJson(snapped),
                    TravelMode.WALKING.name(),
                    RouteStrategy.FASTEST.name()
            );
            double matrixMs = (System.nanoTime() - matrixStarted) / 1_000_000.0;
            double[][] matrix = toMatrix(stops.size(), matrixRows);
            HeldKarpTourOptimizer optimizer = new HeldKarpTourOptimizer();
            for (int i = 0; i < 10; i++) {
                optimizer.solve(matrix, 0, null, true);
            }
            List<Double> optimizerTimings = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                long started = System.nanoTime();
                optimizer.solve(matrix, 0, null, true);
                optimizerTimings.add((System.nanoTime() - started) / 1_000_000.0);
            }
            Collections.sort(optimizerTimings);
            double optimizerMedianMs = optimizerTimings.get(optimizerTimings.size() / 2);

            // Warm the JVM and database plans, then report a median of three full
            // cost-matrix + order optimization + route-geometry executions.
            TourPlanVO warmup = tourPlanningService.planTour(request);
            assertTour(warmup, stops.size());
            List<Double> timings = new ArrayList<>();
            TourPlanVO measured = null;
            for (int i = 0; i < 3; i++) {
                long started = System.nanoTime();
                measured = tourPlanningService.planTour(request);
                timings.add((System.nanoTime() - started) / 1_000_000.0);
            }
            assertTour(measured, stops.size());
            Collections.sort(timings);
            double tourMedianMs = timings.get(timings.size() / 2);

            System.out.printf(Locale.ROOT,
                    "EVIDENCE poiCount=%d edgeCount=%d indexedQueryMs=%.3f "
                            + "sequentialQueryMs=%.3f querySpeedup=%.2fx "
                            + "tourStops=%d matrixMs=%.3f optimizerMedianMs=%.3f "
                            + "tourMedianMs=%.3f tourAlgorithm=%s optimal=%s%n",
                    poiCount,
                    edgeCount,
                    indexed.medianMs(),
                    sequential.medianMs(),
                    sequential.medianMs() / indexed.medianMs(),
                    stops.size(),
                    matrixMs,
                    optimizerMedianMs,
                    tourMedianMs,
                    measured.getAlgorithm(),
                    measured.getOptimal());
        }
    }

    private long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private double[] firstPoiCoordinate(Connection connection) throws Exception {
        String sql = "SELECT ST_X(geom_local), ST_Y(geom_local) "
                + "FROM public.poi WHERE status = 'active' ORDER BY id LIMIT 1";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return new double[]{result.getDouble(1), result.getDouble(2)};
        }
    }

    private String explainSpatialQuery(
            Connection connection,
            double[] center,
            boolean useIndex
    ) throws Exception {
        try (Statement settings = connection.createStatement()) {
            settings.execute("SET enable_seqscan = " + (useIndex ? "off" : "on"));
            settings.execute("SET enable_indexscan = " + (useIndex ? "on" : "off"));
            settings.execute("SET enable_bitmapscan = " + (useIndex ? "on" : "off"));
        }
        String sql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                + "SELECT count(*) FROM public.poi "
                + "WHERE status = 'active' "
                + "AND geom_local && ST_MakeEnvelope(?, ?, ?, ?, 3857) "
                + "AND ST_Intersects(geom_local, ST_MakeEnvelope(?, ?, ?, ?, 3857))";
        double radius = 500.0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int offset : new int[]{0, 4}) {
                statement.setDouble(offset + 1, center[0] - radius);
                statement.setDouble(offset + 2, center[1] - radius);
                statement.setDouble(offset + 3, center[0] + radius);
                statement.setDouble(offset + 4, center[1] + radius);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private PlanEvidence measureSpatialQuery(
            Connection connection,
            double[] center,
            boolean useIndex
    ) throws Exception {
        List<Double> timings = new ArrayList<>();
        String plan = null;
        for (int i = 0; i < 8; i++) {
            plan = explainSpatialQuery(connection, center, useIndex);
            if (i > 0) {
                timings.add(executionTime(plan));
            }
        }
        Collections.sort(timings);
        return new PlanEvidence(plan, timings.get(timings.size() / 2));
    }

    private double executionTime(String jsonPlan) {
        Matcher matcher = EXECUTION_TIME.matcher(jsonPlan);
        assertThat(matcher.find()).as("EXPLAIN JSON execution time").isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private List<WaypointDTO> roadWaypoints(Connection connection, int count) throws Exception {
        String sql = "WITH candidates AS ("
                + " SELECT ST_X(ST_LineInterpolatePoint(geom_local, 0.5)) AS x,"
                + "        ST_Y(ST_LineInterpolatePoint(geom_local, 0.5)) AS y,"
                + "        row_number() OVER (ORDER BY id) AS rn"
                + " FROM public.road_edge"
                + " WHERE status = 'active' AND COALESCE(access_walk, true)"
                + "   AND (walking_cost_s > 0 OR walking_reverse_s > 0)"
                + ") SELECT x, y FROM candidates WHERE rn % 25 = 0 LIMIT ?";
        List<WaypointDTO> stops = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, count);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    stops.add(WaypointDTO.builder()
                            .point(LocalPointDTO.builder()
                                    .x(result.getDouble(1))
                                    .y(result.getDouble(2))
                                    .build())
                            .build());
                }
            }
        }
        assertThat(stops).hasSize(count);
        return stops;
    }

    private String pointsJson(List<SnappedPoint> points) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            SnappedPoint point = points.get(i);
            json.append("{\"pid\":").append(i + 1)
                    .append(",\"edge_id\":").append(point.edgeId())
                    .append(",\"fraction\":").append(point.fraction())
                    .append(",\"side\":\"b\"}");
        }
        return json.append(']').toString();
    }

    private double[][] toMatrix(int size, List<CostMatrixRow> rows) {
        double[][] matrix = new double[size][size];
        for (double[] row : matrix) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int i = 0; i < size; i++) {
            matrix[i][i] = 0.0;
        }
        for (CostMatrixRow row : rows) {
            int from = Math.toIntExact(row.getStartPid()) - 1;
            int to = Math.toIntExact(row.getEndPid()) - 1;
            if (from >= 0 && from < size && to >= 0 && to < size && row.getAggCost() != null) {
                matrix[from][to] = row.getAggCost();
            }
        }
        return matrix;
    }

    private void assertTour(TourPlanVO tour, int stopCount) {
        assertThat(tour.getVisitOrder()).hasSize(stopCount + 1);
        assertThat(tour.getVisitOrder().getFirst()).isZero();
        assertThat(tour.getVisitOrder().getLast()).isZero();
        assertThat(tour.getLegs()).hasSize(stopCount);
        assertThat(tour.getTotalDistanceLocal()).isPositive();
        assertThat(tour.getTotalDurationSeconds()).isPositive();
        assertThat(tour.getAlgorithm()).isEqualTo(TourAlgorithm.HELD_KARP);
        assertThat(tour.getOptimal()).isTrue();
    }

    private record PlanEvidence(String plan, double medianMs) {
    }
}

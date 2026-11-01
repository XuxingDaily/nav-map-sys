package zhaolong.dai.nav.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;
import zhaolong.dai.nav.cache.CacheKeyFactory;
import zhaolong.dai.nav.cache.CacheProperties;
import zhaolong.dai.nav.cache.PoiDetailCache;
import zhaolong.dai.nav.cache.PoiSpatialCache;
import zhaolong.dai.nav.dto.poi.PoiNearbyDTO;
import zhaolong.dai.nav.poi.mapper.PoiMapper;
import zhaolong.dai.nav.poi.mapper.model.PoiRow;
import zhaolong.dai.nav.poi.service.impl.PoiServiceImpl;
import zhaolong.dai.nav.poi.spatial.LocalPoiGridIndex;
import zhaolong.dai.nav.poi.spatial.PoiSpatialBounds;
import zhaolong.dai.nav.poi.spatial.PoiSpatialQueryService;
import zhaolong.dai.nav.version.DatasetCode;
import zhaolong.dai.nav.version.service.DatasetRevisionService;
import zhaolong.dai.nav.vo.poi.PoiListVO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in benchmark for the real Redis-backed POI grid cache. */
@SpringBootTest(properties =
        "logging.level.zhaolong.dai.nav.poi.service.impl.PoiServiceImpl=OFF")
@EnabledIfSystemProperty(named = "poi.cache.evidence", matches = "true")
class PoiGridCacheEvidenceIntegrationTest {

    private static final long BENCHMARK_REVISION = 9_999_991L;

    @Autowired
    private PoiMapper realMapper;

    @Autowired
    private RedisTemplate<String, byte[]> binaryRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldMeasureColdWarmAndDirectDatabaseQueries() throws Exception {
        double[] center = densestPoiCellCenter();

        for (double radius : new double[]{500.0, 2_000.0, 8_000.0}) {
            BenchmarkFixture fixture = fixture("radius-" + (int) radius, realMapper, 4);
            try {
                PoiNearbyDTO query = query(center, radius);

                long coldStarted = System.nanoTime();
                PoiListVO cold = fixture.service().getByNearby(query);
                double coldMs = elapsedMs(coldStarted);

                for (int i = 0; i < 5; i++) {
                    fixture.service().getByNearby(query);
                    realMapper.selectPoiByCircle(query);
                }

                List<Double> warmTimings = timings(31, () -> fixture.service().getByNearby(query));
                List<Double> databaseTimings = timings(31, () -> realMapper.selectPoiByCircle(query));
                PoiListVO cachedResult = fixture.service().getByNearby(query);
                List<PoiRow> databaseResult = realMapper.selectPoiByCircle(query);

                assertThat(cachedResult.getItems())
                        .extracting("id")
                        .containsExactlyElementsOf(databaseResult.stream().map(PoiRow::getId).toList());

                double warmP50 = percentile(warmTimings, 0.50);
                double warmP95 = percentile(warmTimings, 0.95);
                double databaseP50 = percentile(databaseTimings, 0.50);
                double databaseP95 = percentile(databaseTimings, 0.95);

                System.out.printf(Locale.ROOT,
                        "POI_CACHE_EVIDENCE radius=%.0f resultCount=%d cells=%d "
                                + "coldMs=%.3f warmP50Ms=%.3f warmP95Ms=%.3f "
                                + "databaseP50Ms=%.3f databaseP95Ms=%.3f speedupP50=%.2fx%n",
                        radius,
                        cachedResult.getCount(),
                        fixture.grid().cellsFor(PoiSpatialBounds.around(center[0], center[1], radius)).size(),
                        coldMs,
                        warmP50,
                        warmP95,
                        databaseP50,
                        databaseP95,
                        databaseP50 / warmP50);
            }
            finally {
                fixture.deleteBenchmarkKeys(center, radius);
            }
        }
    }

    @Test
    void shouldCompareGridZoomLevelsForNearbyQueries() throws Exception {
        double[] center = densestPoiCellCenter();
        for (double radius : new double[]{500.0, 2_000.0}) {
            PoiNearbyDTO query = query(center, radius);
            for (int i = 0; i < 5; i++) {
                realMapper.selectPoiByCircle(query);
            }
            List<Double> databaseTimings = timings(21, () -> realMapper.selectPoiByCircle(query));
            double databaseP50 = percentile(databaseTimings, 0.50);

            for (int zoom : new int[]{4, 6, 8, 9}) {
                BenchmarkFixture fixture = fixture(
                        "zoom-" + zoom + "-radius-" + (int) radius,
                        realMapper,
                        zoom
                );
                try {
                    long coldStarted = System.nanoTime();
                    PoiListVO first = fixture.service().getByNearby(query);
                    double coldMs = elapsedMs(coldStarted);
                    for (int i = 0; i < 5; i++) {
                        fixture.service().getByNearby(query);
                    }
                    List<Double> warmTimings = timings(
                            21, () -> fixture.service().getByNearby(query)
                    );
                    double warmP50 = percentile(warmTimings, 0.50);
                    int cells = fixture.grid().cellsFor(
                            PoiSpatialBounds.around(center[0], center[1], radius)
                    ).size();
                    System.out.printf(Locale.ROOT,
                            "POI_GRID_ZOOM radius=%.0f zoom=%d cells=%d resultCount=%d "
                                    + "coldMs=%.3f warmP50Ms=%.3f databaseP50Ms=%.3f "
                                    + "speedupP50=%.2fx%n",
                            radius,
                            zoom,
                            cells,
                            first.getCount(),
                            coldMs,
                            warmP50,
                            databaseP50,
                            databaseP50 / warmP50);
                }
                finally {
                    fixture.deleteBenchmarkKeys(center, radius);
                }
            }
        }
    }

    @Test
    void shouldCoalesceConcurrentColdMissesIntoOneDatabaseLoad() throws Exception {
        double[] center = densestPoiCellCenter();
        double radius = 2_000.0;
        int rounds = 50;
        int concurrency = 8;
        AtomicInteger databaseLoads = new AtomicInteger();
        PoiMapper countingMapper = mock(PoiMapper.class);
        when(countingMapper.selectByBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(invocation -> {
                    databaseLoads.incrementAndGet();
                    return realMapper.selectByBounds(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3)
                    );
                });

        BenchmarkFixture fixture = fixture("concurrent", countingMapper, 4);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            PoiNearbyDTO query = query(center, radius);
            List<Double> roundTimings = new ArrayList<>(rounds);
            Integer expectedResultCount = null;

            for (int round = 1; round <= rounds; round++) {
                fixture.deleteBenchmarkKeys(center, radius);
                int loadsBeforeRound = databaseLoads.get();
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<PoiListVO>> futures = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return fixture.service().getByNearby(query);
                    }));
                }

                ready.await();
                long started = System.nanoTime();
                start.countDown();
                List<Integer> counts = new ArrayList<>();
                for (Future<PoiListVO> future : futures) {
                    counts.add(future.get().getCount());
                }
                roundTimings.add(elapsedMs(started));

                int roundResultCount = counts.getFirst();
                assertThat(counts).allMatch(count -> count.equals(roundResultCount));
                if (expectedResultCount == null) {
                    expectedResultCount = roundResultCount;
                }
                assertThat(roundResultCount).isEqualTo(expectedResultCount);
                assertThat(databaseLoads.get() - loadsBeforeRound)
                        .as("database loads in cold-cache round %d", round)
                        .isEqualTo(1);
            }

            Collections.sort(roundTimings);
            int totalRequests = rounds * concurrency;
            double coalescedPercent = 100.0 * (totalRequests - databaseLoads.get()) / totalRequests;
            assertThat(databaseLoads).hasValue(rounds);
            System.out.printf(Locale.ROOT,
                    "POI_CACHE_CONCURRENCY rounds=%d concurrency=%d requests=%d "
                            + "databaseLoads=%d coalescedPercent=%.1f "
                            + "roundP50Ms=%.3f roundP95Ms=%.3f resultCount=%d%n",
                    rounds,
                    concurrency,
                    totalRequests,
                    databaseLoads.get(),
                    coalescedPercent,
                    percentile(roundTimings, 0.50),
                    percentile(roundTimings, 0.95),
                    expectedResultCount);
        }
        finally {
            executor.shutdownNow();
            fixture.deleteBenchmarkKeys(center, radius);
        }
    }

    private BenchmarkFixture fixture(String label, PoiMapper mapper, int zoom) {
        CacheProperties properties = new CacheProperties();
        properties.setKeyPrefix("nav:benchmark:" + label + ":" + UUID.randomUUID());
        properties.setCacheSchemaVersion(1);
        properties.setPoiSpatialZoom(zoom);
        properties.setPoiSpatialTtl(Duration.ofMinutes(5));

        CacheKeyFactory keyFactory = new CacheKeyFactory(properties);
        PoiSpatialCache cache = new PoiSpatialCache(
                binaryRedisTemplate, keyFactory, properties, objectMapper
        );
        LocalPoiGridIndex grid = new LocalPoiGridIndex(properties);
        DatasetRevisionService revisions = fixedRevision();
        PoiSpatialQueryService spatial = new PoiSpatialQueryService(
                grid, cache, revisions, mapper
        );
        PoiServiceImpl service = new PoiServiceImpl(
                realMapper, spatial, mock(PoiDetailCache.class), revisions
        );
        return new BenchmarkFixture(service, grid, keyFactory, binaryRedisTemplate);
    }

    private DatasetRevisionService fixedRevision() {
        return new DatasetRevisionService() {
            @Override
            public long current(DatasetCode datasetCode) {
                return BENCHMARK_REVISION;
            }

            @Override
            public long incrementAndGet(DatasetCode datasetCode) {
                throw new UnsupportedOperationException("benchmark is read-only");
            }
        };
    }

    private PoiNearbyDTO query(double[] center, double radius) {
        PoiNearbyDTO query = new PoiNearbyDTO();
        query.setX(center[0]);
        query.setY(center[1]);
        query.setRadius(radius);
        query.setLimit(10_000);
        return query;
    }

    private double[] densestPoiCellCenter() throws Exception {
        String sql = "WITH points AS ("
                + " SELECT ST_X(geom_local) AS x, ST_Y(geom_local) AS y"
                + " FROM public.poi WHERE status = 'active'"
                + ") SELECT avg(x), avg(y) FROM points"
                + " GROUP BY floor((x - 12550000.0) / 16384.0),"
                + "          floor((2800000.0 - y) / 16384.0)"
                + " ORDER BY count(*) DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return new double[]{result.getDouble(1), result.getDouble(2)};
        }
    }

    private List<Double> timings(int iterations, ThrowingRunnable operation) throws Exception {
        List<Double> values = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            operation.run();
            values.add(elapsedMs(started));
        }
        Collections.sort(values);
        return values;
    }

    private double percentile(List<Double> sorted, double percentile) {
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }

    private double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record BenchmarkFixture(
            PoiServiceImpl service,
            LocalPoiGridIndex grid,
            CacheKeyFactory keyFactory,
            RedisTemplate<String, byte[]> redisTemplate
    ) {
        private void deleteBenchmarkKeys(double[] center, double radius) {
            Collection<String> keys = grid.cellsFor(
                            PoiSpatialBounds.around(center[0], center[1], radius)
                    ).stream()
                    .map(cell -> keyFactory.poiCell(
                            BENCHMARK_REVISION, cell.z(), cell.x(), cell.y()
                    ))
                    .toList();
            redisTemplate.delete(keys);
        }
    }
}

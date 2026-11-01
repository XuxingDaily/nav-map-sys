package zhaolong.dai.nav.poi.spatial;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zhaolong.dai.nav.cache.PoiSpatialCache;
import zhaolong.dai.nav.poi.mapper.PoiMapper;
import zhaolong.dai.nav.poi.mapper.model.PoiRow;
import zhaolong.dai.nav.version.DatasetCode;
import zhaolong.dai.nav.version.service.DatasetRevisionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PoiSpatialQueryServiceTest {

    private final LocalPoiGridIndex grid = mock(LocalPoiGridIndex.class);
    private final PoiSpatialCache cache = mock(PoiSpatialCache.class);
    private final DatasetRevisionService revisions = mock(DatasetRevisionService.class);
    private final PoiMapper mapper = mock(PoiMapper.class);
    private final PoiSpatialQueryService service = new PoiSpatialQueryService(grid, cache, revisions, mapper);

    @Test
    void shouldBatchLoadOnlyMissingCellsAndWriteBackEmptyBuckets() {
        PoiSpatialBounds query = new PoiSpatialBounds(0, 0, 20, 10);
        PoiSpatialBounds missingBounds = new PoiSpatialBounds(10, 0, 20, 10);
        PoiCellCoordinate first = new PoiCellCoordinate(4, 1, 1);
        PoiCellCoordinate second = new PoiCellCoordinate(4, 2, 1);
        PoiCacheItem cached = item(1, 5, 5);
        PoiRow loaded = row(2, 15, 5);

        when(grid.cellsFor(query)).thenReturn(List.of(first, second));
        when(revisions.current(DatasetCode.POI)).thenReturn(7L);
        when(cache.getAll(7L, List.of(first, second)))
                .thenReturn(Map.of(first, List.of(cached)));
        when(cache.getAll(eq(7L), any(Set.class))).thenReturn(Map.of());
        when(grid.coveringBounds(Set.of(second))).thenReturn(missingBounds);
        when(mapper.selectByBounds(10, 0, 20, 10)).thenReturn(List.of(loaded));
        when(grid.cellFor(15, 5)).thenReturn(second);

        List<PoiCacheItem> result = service.findCandidates(query);

        assertThat(result).extracting(PoiCacheItem::id).containsExactly(1L, 2L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<PoiCellCoordinate, List<PoiCacheItem>>> written =
                ArgumentCaptor.forClass(Map.class);
        verify(cache).putAll(eq(7L), written.capture());
        assertThat(written.getValue()).containsOnlyKeys(second);
        assertThat(written.getValue().get(second)).extracting(PoiCacheItem::id).containsExactly(2L);
    }

    @Test
    void shouldServeCompleteCacheHitWithoutDatabaseAccess() {
        PoiSpatialBounds query = new PoiSpatialBounds(0, 0, 10, 10);
        PoiCellCoordinate cell = new PoiCellCoordinate(4, 1, 1);
        when(grid.cellsFor(query)).thenReturn(List.of(cell));
        when(revisions.current(DatasetCode.POI)).thenReturn(3L);
        when(cache.getAll(3L, List.of(cell))).thenReturn(
                new LinkedHashMap<>(Map.of(cell, List.of(item(1, 2, 2))))
        );

        assertThat(service.findCandidates(query)).hasSize(1);

        verify(mapper, never()).selectByBounds(any(Double.class), any(Double.class), any(Double.class), any(Double.class));
        verify(cache, never()).putAll(eq(3L), any());
    }

    private PoiCacheItem item(long id, double x, double y) {
        return PoiCacheItem.from(row(id, x, y));
    }

    private PoiRow row(long id, double x, double y) {
        return PoiRow.builder()
                .id(id)
                .sourceId(UUID.randomUUID())
                .name("poi-" + id)
                .x(x)
                .y(y)
                .dataVersion(1L)
                .build();
    }
}

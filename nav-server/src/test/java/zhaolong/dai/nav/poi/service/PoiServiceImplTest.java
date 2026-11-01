package zhaolong.dai.nav.poi.service;

import org.junit.jupiter.api.Test;
import zhaolong.dai.nav.cache.PoiDetailCache;
import zhaolong.dai.nav.dto.poi.PoiNearbyDTO;
import zhaolong.dai.nav.dto.poi.PoiViewportDTO;
import zhaolong.dai.nav.poi.mapper.PoiMapper;
import zhaolong.dai.nav.poi.service.impl.PoiServiceImpl;
import zhaolong.dai.nav.poi.spatial.PoiCacheItem;
import zhaolong.dai.nav.poi.spatial.PoiSpatialBounds;
import zhaolong.dai.nav.poi.spatial.PoiSpatialQueryService;
import zhaolong.dai.nav.version.service.DatasetRevisionService;
import zhaolong.dai.nav.vo.poi.PoiListVO;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PoiServiceImplTest {

    private final PoiMapper mapper = mock(PoiMapper.class);
    private final PoiSpatialQueryService spatial = mock(PoiSpatialQueryService.class);
    private final PoiDetailCache detailCache = mock(PoiDetailCache.class);
    private final DatasetRevisionService revisions = mock(DatasetRevisionService.class);
    private final PoiServiceImpl service = new PoiServiceImpl(mapper, spatial, detailCache, revisions);

    @Test
    void nearbyShouldApplyExactCircleFilterThenSortByDistance() {
        when(spatial.findCandidates(any(PoiSpatialBounds.class))).thenReturn(List.of(
                item(3, 8, 8),
                item(2, 3, 4),
                item(1, 1, 1)
        ));
        PoiNearbyDTO query = new PoiNearbyDTO();
        query.setX(0.0);
        query.setY(0.0);
        query.setRadius(5.0);
        query.setLimit(10);

        PoiListVO result = service.getByNearby(query);

        assertThat(result.getItems()).extracting("id").containsExactly(1L, 2L);
        assertThat(result.getCount()).isEqualTo(2);
        verify(spatial).findCandidates(new PoiSpatialBounds(-5, -5, 5, 5));
    }

    @Test
    void viewportShouldFilterCandidatesAndApplyLimitDeterministically() {
        when(spatial.findCandidates(any(PoiSpatialBounds.class))).thenReturn(List.of(
                item(7, 5, 5),
                item(2, 1, 1),
                item(1, 20, 20)
        ));
        PoiViewportDTO query = new PoiViewportDTO();
        query.setViewport(new Double[]{0.0, 0.0, 10.0, 10.0});
        query.setLimit(1);

        PoiListVO result = service.getByViewport(query);

        assertThat(result.getItems()).extracting("id").containsExactly(2L);
        assertThat(result.getCount()).isOne();
    }

    @Test
    void viewportShouldRejectMalformedBoundsBeforeQueryingCache() {
        PoiViewportDTO query = new PoiViewportDTO();
        query.setViewport(new Double[]{10.0, 0.0, 5.0, 10.0});

        assertThatThrownBy(() -> service.getByViewport(query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PoiCacheItem item(long id, double x, double y) {
        return new PoiCacheItem(
                id, UUID.randomUUID(), "poi-" + id, null, null, null,
                null, null, x, y, 1L
        );
    }
}

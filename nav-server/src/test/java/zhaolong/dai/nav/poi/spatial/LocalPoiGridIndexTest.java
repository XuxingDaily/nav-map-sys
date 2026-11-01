package zhaolong.dai.nav.poi.spatial;

import org.junit.jupiter.api.Test;
import zhaolong.dai.nav.cache.CacheProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPoiGridIndexTest {

    @Test
    void shouldRoundTripCellsToCoveringBounds() {
        LocalPoiGridIndex index = indexAtZoom(4);
        PoiSpatialBounds query = new PoiSpatialBounds(12_606_000, 2_635_000, 12_625_000, 2_650_000);

        List<PoiCellCoordinate> cells = index.cellsFor(query);
        PoiSpatialBounds covering = index.coveringBounds(cells);

        assertThat(cells).isNotEmpty();
        assertThat(covering.minX()).isLessThanOrEqualTo(query.minX());
        assertThat(covering.minY()).isLessThanOrEqualTo(query.minY());
        assertThat(covering.maxX()).isGreaterThanOrEqualTo(query.maxX());
        assertThat(covering.maxY()).isGreaterThanOrEqualTo(query.maxY());
        assertThat(cells).allSatisfy(cell -> {
            assertThat(cell.z()).isEqualTo(4);
            assertThat(cell.x()).isBetween(0, 15);
            assertThat(cell.y()).isBetween(0, 15);
        });
    }

    @Test
    void shouldReturnNoCellsForBoundsOutsideLocalWorld() {
        LocalPoiGridIndex index = indexAtZoom(4);

        assertThat(index.cellsFor(new PoiSpatialBounds(0, 0, 100, 100))).isEmpty();
    }

    @Test
    void shouldClampPointOnWorldMaximumToLastCell() {
        LocalPoiGridIndex index = indexAtZoom(4);

        assertThat(index.cellFor(12_550_000 + 262_144, 2_800_000 - 262_144))
                .isEqualTo(new PoiCellCoordinate(4, 15, 15));
    }

    @Test
    void shouldRejectInvalidZoomAndEmptyCoveringSet() {
        CacheProperties properties = new CacheProperties();
        properties.setPoiSpatialZoom(23);

        assertThatThrownBy(() -> new LocalPoiGridIndex(properties))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> indexAtZoom(4).coveringBounds(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LocalPoiGridIndex indexAtZoom(int zoom) {
        CacheProperties properties = new CacheProperties();
        properties.setPoiSpatialZoom(zoom);
        return new LocalPoiGridIndex(properties);
    }
}

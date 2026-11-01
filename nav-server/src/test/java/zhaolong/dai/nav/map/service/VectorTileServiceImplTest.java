package zhaolong.dai.nav.map.service;

import org.junit.jupiter.api.Test;
import zhaolong.dai.nav.cache.VectorTileCache;
import zhaolong.dai.nav.exception.MapLayerNotFoundException;
import zhaolong.dai.nav.map.mapper.VectorTileMapper;
import zhaolong.dai.nav.map.mapper.model.VectorTileRow;
import zhaolong.dai.nav.map.service.impl.VectorTileServiceImpl;
import zhaolong.dai.nav.version.DatasetCode;
import zhaolong.dai.nav.version.service.DatasetRevisionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorTileServiceImplTest {

    private final VectorTileMapper mapper = mock(VectorTileMapper.class);
    private final VectorTileCache cache = mock(VectorTileCache.class);
    private final DatasetRevisionService revisions = mock(DatasetRevisionService.class);
    private final VectorTileServiceImpl service = new VectorTileServiceImpl(mapper, cache, revisions);

    @Test
    void shouldReturnCacheHitWithoutGeneratingTile() {
        byte[] cached = {1, 2, 3};
        when(revisions.current(DatasetCode.MAP)).thenReturn(4L);
        when(cache.get("base", 4L, 8, 100, 100)).thenReturn(cached);

        assertThat(service.getTile("base", 8, 100, 100)).isSameAs(cached);

        verify(mapper, never()).selectBaseVectorTile(8, 100, 100);
    }

    @Test
    void shouldGenerateAndCacheBaseTileOnMiss() {
        byte[] generated = {9, 8};
        VectorTileRow row = new VectorTileRow();
        row.setTile(generated);
        when(revisions.current(DatasetCode.MAP)).thenReturn(5L);
        when(mapper.selectBaseVectorTile(8, 100, 100)).thenReturn(row);

        assertThat(service.getTile("base", 8, 100, 100)).isSameAs(generated);
        verify(cache).put("base", 5L, 8, 100, 100, generated);
    }

    @Test
    void shouldRejectUnknownLayerAndInvalidCoordinates() {
        when(revisions.current(DatasetCode.MAP)).thenReturn(1L);
        when(mapper.existsEnabledLayer("missing")).thenReturn(false);

        assertThatThrownBy(() -> service.getTile("missing", 1, 0, 0))
                .isInstanceOf(MapLayerNotFoundException.class);
        assertThatThrownBy(() -> service.getTile("base", 23, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getTile("base", 2, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

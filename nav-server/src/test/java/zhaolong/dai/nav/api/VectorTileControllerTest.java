package zhaolong.dai.nav.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import zhaolong.dai.nav.constans.MessageConstants;
import zhaolong.dai.nav.exception.MapLayerNotFoundException;
import zhaolong.dai.nav.map.controller.VectorTileController;
import zhaolong.dai.nav.map.mapper.model.MapBoundsRow;
import zhaolong.dai.nav.map.service.VectorTileService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VectorTileControllerTest extends ControllerTestSupport {

    private VectorTileService vectorTileService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        vectorTileService = mock(VectorTileService.class);
        mockMvc = standaloneMockMvc(new VectorTileController(vectorTileService));
    }

    @Test
    void shouldReturnAdministrativeBounds() throws Exception {
        MapBoundsRow bounds = new MapBoundsRow();
        bounds.setMinX(12_607_000.0);
        bounds.setMinY(2_619_000.0);
        bounds.setMaxX(12_686_000.0);
        bounds.setMaxY(2_695_000.0);
        when(vectorTileService.getAdminBounds()).thenReturn(bounds);

        mockMvc.perform(get("/api/v1/map/tiles/bounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minX").value(12_607_000.0))
                .andExpect(jsonPath("$.maxY").value(2_695_000.0));
    }

    @Test
    void shouldReturnMvtWithCacheHeaders() throws Exception {
        byte[] tile = new byte[]{0x1a, 0x02, 0x08, 0x01};
        when(vectorTileService.getTile("base", 14, 4500, 10062)).thenReturn(tile);

        mockMvc.perform(get("/api/v1/map/tiles/base/14/4500/10062.mvt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.mapbox-vector-tile"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(header().longValue("Content-Length", tile.length))
                .andExpect(content().bytes(tile));

        verify(vectorTileService).getTile("base", 14, 4500, 10062);
    }

    @Test
    void shouldMapIllegalTileCoordinatesToBadRequest() throws Exception {
        when(vectorTileService.getTile("base", 23, 0, 0))
                .thenThrow(new IllegalArgumentException(MessageConstants.TILE_ZOOM_INVALID));

        mockMvc.perform(get("/api/v1/map/tiles/base/23/0/0.mvt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.TILE_ZOOM_INVALID));
    }

    @Test
    void shouldHideUnexpectedLayerExceptionBehindGenericServerError() throws Exception {
        when(vectorTileService.getTile("missing", 10, 1, 1))
                .thenThrow(new MapLayerNotFoundException(MessageConstants.MAP_LAYER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/map/tiles/missing/10/1/1.mvt"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.INTERNAL_SERVER_ERROR));
    }
}

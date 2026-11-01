package zhaolong.dai.nav.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import zhaolong.dai.nav.constans.MessageConstants;
import zhaolong.dai.nav.dto.poi.PoiKeywordDTO;
import zhaolong.dai.nav.dto.poi.PoiNearbyDTO;
import zhaolong.dai.nav.dto.poi.PoiViewportDTO;
import zhaolong.dai.nav.exception.PoiNotFoundException;
import zhaolong.dai.nav.poi.controller.PoiController;
import zhaolong.dai.nav.poi.service.PoiService;
import zhaolong.dai.nav.vo.LocalPointVO;
import zhaolong.dai.nav.vo.poi.PoiListVO;
import zhaolong.dai.nav.vo.poi.PoiVO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PoiControllerTest extends ControllerTestSupport {

    private PoiService poiService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        poiService = mock(PoiService.class);
        mockMvc = standaloneMockMvc(new PoiController(poiService));
    }

    @Test
    void shouldSearchPoiByKeyword() throws Exception {
        PoiVO poi = PoiVO.builder()
                .id(1001L)
                .name("广州大学")
                .location(LocalPointVO.builder().x(12_614_078.2).y(2_648_801.5).build())
                .dataVersion(5L)
                .build();
        when(poiService.getByKeyword(any())).thenReturn(PoiListVO.builder()
                .keyword("大学")
                .items(List.of(poi))
                .count(1)
                .build());

        mockMvc.perform(get("/api/v1/pois")
                        .param("keyword", " 大学 ")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(1001))
                .andExpect(jsonPath("$.data.items[0].name").value("广州大学"));

        ArgumentCaptor<PoiKeywordDTO> query = ArgumentCaptor.forClass(PoiKeywordDTO.class);
        verify(poiService).getByKeyword(query.capture());
        assertThat(query.getValue().getNormalizedKeyword()).isEqualTo("大学");
        assertThat(query.getValue().getLimit()).isEqualTo(20);
    }

    @Test
    void shouldSearchNearbyPoi() throws Exception {
        when(poiService.getByNearby(any())).thenReturn(PoiListVO.builder()
                .x(12_614_078.2)
                .y(2_648_801.5)
                .radius(500.0)
                .items(List.of())
                .count(0)
                .build());

        mockMvc.perform(get("/api/v1/pois/nearby")
                        .param("x", "12614078.2")
                        .param("y", "2648801.5")
                        .param("radius", "500")
                        .param("limit", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.radius").value(500.0))
                .andExpect(jsonPath("$.data.count").value(0));

        ArgumentCaptor<PoiNearbyDTO> query = ArgumentCaptor.forClass(PoiNearbyDTO.class);
        verify(poiService).getByNearby(query.capture());
        assertThat(query.getValue().getLimit()).isEqualTo(30);
    }

    @Test
    void shouldSearchPoiInViewport() throws Exception {
        when(poiService.getByViewport(any())).thenReturn(PoiListVO.builder()
                .bbox(new Double[]{12_613_000.0, 2_648_000.0, 12_615_000.0, 2_650_000.0})
                .items(List.of())
                .count(0)
                .build());

        mockMvc.perform(get("/api/v1/pois/viewport")
                        .param("viewport", "12613000", "2648000", "12615000", "2650000")
                        .param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.bbox.length()").value(4));

        ArgumentCaptor<PoiViewportDTO> query = ArgumentCaptor.forClass(PoiViewportDTO.class);
        verify(poiService).getByViewport(query.capture());
        assertThat(query.getValue().getViewport()).containsExactly(
                12_613_000.0, 2_648_000.0, 12_615_000.0, 2_650_000.0
        );
    }

    @Test
    void shouldRejectIncompleteNearbyQueryBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/pois/nearby")
                        .param("x", "12614078.2")
                        .param("radius", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void shouldReturnPoiDetails() throws Exception {
        when(poiService.getById(1001L)).thenReturn(PoiVO.builder()
                .id(1001L)
                .name("广州大学")
                .category("university")
                .location(LocalPointVO.builder().x(12_614_078.2).y(2_648_801.5).build())
                .dataVersion(5L)
                .build());

        mockMvc.perform(get("/api/v1/pois/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.name").value("广州大学"))
                .andExpect(jsonPath("$.data.location.x").value(12_614_078.2));
    }

    @Test
    void shouldReturnNotFoundForMissingPoi() throws Exception {
        when(poiService.getById(999999L))
                .thenThrow(new PoiNotFoundException(MessageConstants.POI_NOT_FOUND_MSG));

        mockMvc.perform(get("/api/v1/pois/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.POI_NOT_FOUND_MSG));
    }
}

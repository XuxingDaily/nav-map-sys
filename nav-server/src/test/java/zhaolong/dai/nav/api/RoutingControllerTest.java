package zhaolong.dai.nav.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import zhaolong.dai.nav.constans.MessageConstants;
import zhaolong.dai.nav.enums.routing.RouteStrategy;
import zhaolong.dai.nav.enums.routing.TourAlgorithm;
import zhaolong.dai.nav.enums.routing.TravelMode;
import zhaolong.dai.nav.exception.RoutingNotFoundException;
import zhaolong.dai.nav.routing.controller.RoutingController;
import zhaolong.dai.nav.routing.service.RoadSnapService;
import zhaolong.dai.nav.routing.service.RoutePlanningService;
import zhaolong.dai.nav.routing.service.TourPlanningService;
import zhaolong.dai.nav.vo.LocalPointVO;
import zhaolong.dai.nav.vo.routing.RouteVO;
import zhaolong.dai.nav.vo.routing.SnappedPointVO;
import zhaolong.dai.nav.vo.routing.TourPlanVO;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoutingControllerTest extends ControllerTestSupport {

    private RoadSnapService roadSnapService;
    private RoutePlanningService routePlanningService;
    private TourPlanningService tourPlanningService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roadSnapService = mock(RoadSnapService.class);
        routePlanningService = mock(RoutePlanningService.class);
        tourPlanningService = mock(TourPlanningService.class);
        mockMvc = standaloneMockMvc(new RoutingController(
                roadSnapService, routePlanningService, tourPlanningService
        ));
    }

    @Test
    void shouldSnapPointToRoad() throws Exception {
        when(roadSnapService.snap(any())).thenReturn(SnappedPointVO.builder()
                .edgeId(251L)
                .fraction(0.374)
                .snappedPoint(LocalPointVO.builder().x(12_614_080.1).y(2_648_799.8).build())
                .distanceLocal(2.55)
                .networkVersion(3L)
                .build());

        mockMvc.perform(post("/api/v1/routing/snap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "point":{"x":12614078.2,"y":2648801.5},
                                  "travelMode":"WALKING",
                                  "maxSnapDistanceLocal":100.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.edgeId").value(251))
                .andExpect(jsonPath("$.data.networkVersion").value(3));

        verify(roadSnapService).snap(any());
    }

    @Test
    void shouldPlanTwoPointRoute() throws Exception {
        when(routePlanningService.route(any())).thenReturn(RouteVO.builder()
                .travelMode(TravelMode.WALKING)
                .strategy(RouteStrategy.FASTEST)
                .distanceLocal(2850.4)
                .durationSeconds(428.7)
                .geometryWkt("LINESTRING(0 0,1 1)")
                .networkVersion(3L)
                .build());

        mockMvc.perform(post("/api/v1/routing/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin":{"poiId":97},
                                  "destination":{"poiId":26200},
                                  "travelMode":"WALKING",
                                  "strategy":"FASTEST",
                                  "maxSnapDistanceLocal":1000.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.travelMode").value("WALKING"))
                .andExpect(jsonPath("$.data.distanceLocal").value(2850.4))
                .andExpect(jsonPath("$.data.geometryWkt").value("LINESTRING(0 0,1 1)"));
    }

    @Test
    void shouldPlanTour() throws Exception {
        when(tourPlanningService.planTour(any())).thenReturn(TourPlanVO.builder()
                .visitOrder(List.of(0, 1, 2, 0))
                .travelMode(TravelMode.WALKING)
                .strategy(RouteStrategy.FASTEST)
                .roundTrip(true)
                .algorithm(TourAlgorithm.HELD_KARP)
                .optimal(true)
                .optimizedCost(100.0)
                .optimizedCostUnit("SECONDS")
                .totalDistanceLocal(250.0)
                .totalDurationSeconds(100.0)
                .legs(List.of())
                .snappedStops(List.of())
                .networkVersion(3L)
                .build());

        mockMvc.perform(post("/api/v1/routing/tours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stops":[{"poiId":97},{"poiId":26200},{"poiId":1001}],
                                  "algorithm":"AUTO",
                                  "travelMode":"WALKING",
                                  "strategy":"FASTEST",
                                  "roundTrip":true,
                                  "fixedStartIndex":0,
                                  "maxSnapDistanceLocal":1000.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.visitOrder.length()").value(4))
                .andExpect(jsonPath("$.data.algorithm").value("HELD_KARP"))
                .andExpect(jsonPath("$.data.optimal").value(true));
    }

    @Test
    void shouldRejectRouteWithoutDestination() throws Exception {
        mockMvc.perform(post("/api/v1/routing/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origin":{"poiId":97},"travelMode":"WALKING"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.ROUTE_DESTINATION_REQUIRED));

        verifyNoInteractions(routePlanningService);
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/routing/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.REQUEST_PARAMETER_INVALID));
    }

    @Test
    void shouldRejectTourWithFewerThanTwoStops() throws Exception {
        mockMvc.perform(post("/api/v1/routing/tours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stops":[{"poiId":97}],"roundTrip":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.TOUR_STOP_COUNT_INVALID));

        verifyNoInteractions(tourPlanningService);
    }

    @Test
    void shouldReturnNotFoundWhenRouteIsUnreachable() throws Exception {
        when(routePlanningService.route(any()))
                .thenThrow(new RoutingNotFoundException(MessageConstants.ROUTE_NOT_FOUND));

        mockMvc.perform(post("/api/v1/routing/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin":{"poiId":97},
                                  "destination":{"poiId":26200}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(MessageConstants.ROUTE_NOT_FOUND));
    }
}

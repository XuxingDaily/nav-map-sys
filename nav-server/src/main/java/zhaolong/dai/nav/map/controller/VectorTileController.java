package zhaolong.dai.nav.map.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zhaolong.dai.nav.map.service.VectorTileService;
import zhaolong.dai.nav.map.mapper.model.MapBoundsRow;

import java.time.Duration;

@Tag(
        name = "Vector Tile",
        description = "局部平面坐标矢量瓦片服务"
)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/map/tiles")
public class VectorTileController {

    private static final MediaType MVT_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.mapbox-vector-tile"
            );

    private final VectorTileService vectorTileService;

    @Operation(summary = "获取广州行政区范围")
    @GetMapping("/bounds")
    public MapBoundsRow getAdminBounds() {
        return vectorTileService.getAdminBounds();
    }

    @Operation(
            summary = "获取矢量瓦片",
            description = "根据图层编码以及 z/x/y 返回 MVT 二进制瓦片"
    )
    @GetMapping(
            value = "/{layerCode}/{z}/{x}/{y}.mvt",
            produces = "application/vnd.mapbox-vector-tile"
    )
    public ResponseEntity<byte[]> getTile(
            @PathVariable String layerCode,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y
    ) {
        byte[] tile = vectorTileService.getTile(
                layerCode,
                z,
                x,
                y
        );

        return ResponseEntity.ok()
                .contentType(MVT_MEDIA_TYPE)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .contentLength(tile.length)
                .body(tile);
    }

}

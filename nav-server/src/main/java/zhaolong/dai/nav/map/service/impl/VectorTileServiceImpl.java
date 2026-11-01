package zhaolong.dai.nav.map.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zhaolong.dai.nav.cache.VectorTileCache;
import zhaolong.dai.nav.constans.MessageConstants;
import zhaolong.dai.nav.exception.MapLayerNotFoundException;
import zhaolong.dai.nav.map.mapper.VectorTileMapper;
import zhaolong.dai.nav.map.mapper.model.VectorTileRow;
import zhaolong.dai.nav.map.mapper.model.MapBoundsRow;
import zhaolong.dai.nav.map.service.VectorTileService;
import zhaolong.dai.nav.version.DatasetCode;
import zhaolong.dai.nav.version.service.DatasetRevisionService;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorTileServiceImpl implements VectorTileService {

    private static final String BASE_TILESET_CODE = "base";
    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 22;

    private final VectorTileMapper vectorTileMapper;
    private final VectorTileCache vectorTileCache;
    private final DatasetRevisionService datasetRevisionService;

    @Override
    public MapBoundsRow getAdminBounds() {
        return vectorTileMapper.selectAdminBounds();
    }

    /**
     * @param layerCode 图层编码
     * @param z         层级
     * @param x         列号
     * @param y         行号
     * @return MVT
     */
    @Override
    public byte[] getTile(String layerCode, int z, int x, int y) {
        validateTileCoordinate(z, x, y);

        long version = datasetRevisionService.current(DatasetCode.MAP);

        // Cache Aside
        byte[] cached = vectorTileCache.get(layerCode, version, z, x, y);
        if (cached != null) {
            log.debug(
                    "MVT cache HIT: layer={}, version={}, z={}, x={}, y={}",
                    layerCode,
                    version,
                    z,
                    x,
                    y
            );
            return cached;
        }
        log.debug(
                "MVT cache MISS: layer={}, version={}, z={}, x={}, y={}",
                layerCode,
                version,
                z,
                x,
                y
        );

        byte[] tile = generateTile(layerCode, z, x, y);

        vectorTileCache.put(layerCode, version, z, x, y, tile);

        return tile;
    }

    private byte[] generateTile(String layerCode, int z, int x, int y) {
        VectorTileRow row;
        if (BASE_TILESET_CODE.equals(layerCode)) {
            row = vectorTileMapper.selectBaseVectorTile(z, x, y);
        } else if (!vectorTileMapper.existsEnabledLayer(layerCode)) {
            throw new MapLayerNotFoundException(MessageConstants.MAP_LAYER_NOT_FOUND + "：" + layerCode);
        } else {
            row = vectorTileMapper.selectVectorTile(layerCode, z, x, y);
        }

        if (row == null || row.getTile() == null) {
            return new byte[0];
        } else {
            return row.getTile();
        }
    }

    /**
     * 验证 z/x/y 是否属于合法瓦片坐标。
     * <p>
     * z 层级一共有 2^z 行和 2^z 列，因此：
     * <p>
     * 0 <= x < 2^z
     * 0 <= y < 2^z
     */
    private void validateTileCoordinate(int z, int x, int y) {
        if (z < MIN_ZOOM || z > MAX_ZOOM) {
            throw new IllegalArgumentException(
                    MessageConstants.TILE_ZOOM_INVALID
            );
        }

        long tileCount = 1L << z;

        if (x < 0 || y < 0 || x >= tileCount || y >= tileCount) {
            throw new IllegalArgumentException(
                    MessageConstants.TILE_COORDINATE_INVALID
            );
        }
    }

}

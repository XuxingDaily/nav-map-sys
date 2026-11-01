package zhaolong.dai.nav.map.service;

import zhaolong.dai.nav.map.mapper.model.MapBoundsRow;

public interface VectorTileService {

    /** Returns the Guangzhou administrative extent in local map coordinates. */
    MapBoundsRow getAdminBounds();

    /**
     * 生成指定图层的局部矢量瓦片。
     *
     * @param layerCode 图层编码
     * @param z 层级
     * @param x 列号
     * @param y 行号
     * @return MVT 二进制；空瓦片返回长度为 0 的数组
     */
    byte[] getTile(
            String layerCode,
            int z,
            int x,
            int y
    );

}

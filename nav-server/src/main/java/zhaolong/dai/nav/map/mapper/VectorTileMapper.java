package zhaolong.dai.nav.map.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zhaolong.dai.nav.map.mapper.model.VectorTileRow;
import zhaolong.dai.nav.map.mapper.model.MapBoundsRow;

@Mapper
public interface VectorTileMapper {

    /** Returns the merged Guangzhou administrative extent in EPSG:3857. */
    MapBoundsRow selectAdminBounds();

    /**
     * 判断图层是否存在且已经启用。
     *
     * @param layerCode 图层编码
     * @return 是否存在且启用
     */
    boolean existsEnabledLayer(@Param("layerCode") String layerCode);

    /**
     * 调用 PostGIS 函数生成局部坐标矢量瓦片。
     *
     * @param layerCode 图层编码
     * @param z 瓦片层级
     * @param x 瓦片列号
     * @param y 瓦片行号
     * @return MVT 二进制
     */
    VectorTileRow selectVectorTile(
            @Param("layerCode") String layerCode,
            @Param("z") int z,
            @Param("x") int x,
            @Param("y") int y
    );

    /**
     * 一次生成包含多个命名图层的基础地图瓦片。
     *
     * @param z 瓦片层级
     * @param x 瓦片列号
     * @param y 瓦片行号
     * @return 组合 MVT 二进制
     */
    VectorTileRow selectBaseVectorTile(
            @Param("z") int z,
            @Param("x") int x,
            @Param("y") int y
    );

}

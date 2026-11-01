package zhaolong.dai.nav.poi.spatial;

import org.springframework.stereotype.Component;
import zhaolong.dai.nav.cache.CacheProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把局部坐标范围映射到固定层级的 POI 缓存网格。
 */
@Component
public class LocalPoiGridIndex {

    private static final double ORIGIN_X = 12_550_000.0;
    private static final double ORIGIN_Y = 2_800_000.0;
    private static final double WORLD_SPAN = 262_144.0;

    private final int zoom;
    private final int cellCount;
    private final double cellSize;

    public LocalPoiGridIndex(CacheProperties properties) {
        this.zoom = properties.getPoiSpatialZoom();
        if (zoom < 0 || zoom > 22) {
            throw new IllegalArgumentException("POI 空间缓存层级必须在 0～22 之间");
        }
        this.cellCount = 1 << zoom;
        this.cellSize = WORLD_SPAN / cellCount;
    }

    /**
     * 给定一个视口矩形或者附近查询包围盒，计算这个矩形与哪些缓存网格相交。
     * @param bounds 局部平面范围
     * @return 瓦片坐标集合
     */
    public List<PoiCellCoordinate> cellsFor(PoiSpatialBounds bounds) {
        double clippedMinX = Math.max(bounds.minX(), ORIGIN_X);
        double clippedMaxX = Math.min(bounds.maxX(), ORIGIN_X + WORLD_SPAN);
        double clippedMinY = Math.max(bounds.minY(), ORIGIN_Y - WORLD_SPAN);
        double clippedMaxY = Math.min(bounds.maxY(), ORIGIN_Y);

        if (clippedMinX > clippedMaxX || clippedMinY > clippedMaxY) {
            return List.of();
        }

        int startX = cellX(clippedMinX);
        int endX = cellX(clippedMaxX);
        int startY = cellY(clippedMaxY);
        int endY = cellY(clippedMinY);

        List<PoiCellCoordinate> result = new ArrayList<>();
        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                result.add(new PoiCellCoordinate(zoom, x, y));
            }
        }
        return result;
    }

    /**
     * 点归属，给定一个 POI 的平面坐标，计算这个 POI 应该存入哪个缓存网格。
     * @param x 平面横坐标
     * @param y 平面纵坐标
     * @return 瓦片坐标
     */
    public PoiCellCoordinate cellFor(double x, double y) {
        return new PoiCellCoordinate(
                zoom,
                cellX(x),
                cellY(y)
        );
    }

    /**
     * 根据若干 z/x/y 网格坐标，反算出一个能够覆盖这些网格的局部平面坐标范围。
     * @param cells 瓦片坐标集合
     * @return 地理范围
     */
    public PoiSpatialBounds coveringBounds(Collection<PoiCellCoordinate> cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("网格集合不能为空");
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PoiCellCoordinate cell : cells) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minY = Math.min(minY, cell.y());
            maxY = Math.max(maxY, cell.y());
        }

        return new PoiSpatialBounds(
                ORIGIN_X + minX * cellSize,
                ORIGIN_Y - (maxY + 1) * cellSize,
                ORIGIN_X + (maxX + 1) * cellSize,
                ORIGIN_Y - minY * cellSize
        );
    }

    /**
     * 根据局部横坐标计算瓦片横坐标
     * @param x 局部平面横坐标
     * @return 瓦片横坐标
     */
    private int cellX(double x) {
        return clamp((int) Math.floor((x - ORIGIN_X) / cellSize));
    }

    /**
     * 根据局部纵坐标计算瓦片纵坐标
     * @param y 局部平面纵坐标
     * @return 瓦片纵坐标
     */
    private int cellY(double y) {
        return clamp((int) Math.floor((ORIGIN_Y - y) / cellSize));
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(value, cellCount - 1));
    }
}

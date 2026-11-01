package zhaolong.dai.nav.poi.spatial;

import zhaolong.dai.nav.poi.mapper.model.PoiRow;

import java.util.UUID;

/**
 * Redis 空间网格中保存的最小 POI 数据。
 */
public record PoiCacheItem(
        Long id,
        UUID sourceId,
        String name,
        String context,
        String category,
        String basicCategory,
        Double confidence,
        String address,
        double x,
        double y,
        Long dataVersion
) {

    public static PoiCacheItem from(PoiRow row) {
        return new PoiCacheItem(
                row.getId(),
                row.getSourceId(),
                row.getName(),
                row.getContext(),
                row.getCategory(),
                row.getBasicCategory(),
                row.getConfidence(),
                row.getAddress(),
                row.getX(),
                row.getY(),
                row.getDataVersion()
        );
    }
}

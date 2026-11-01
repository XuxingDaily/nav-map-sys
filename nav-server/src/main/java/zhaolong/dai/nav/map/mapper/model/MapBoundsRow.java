package zhaolong.dai.nav.map.mapper.model;

import lombok.Data;

/** Guangzhou administrative extent in the local Web Mercator plane. */
@Data
public class MapBoundsRow {
    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
}

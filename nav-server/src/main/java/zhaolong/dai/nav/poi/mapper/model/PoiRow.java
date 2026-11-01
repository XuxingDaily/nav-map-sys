package zhaolong.dai.nav.poi.mapper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class PoiRow {

    private Long id;
    private UUID sourceId;
    private String name;
    private String context;
    private String category;
    private String basicCategory;
    private Double confidence;
    private String address;
    private Double x;
    private Double y;
    private String coordinateStatus;
    private Long dataVersion;

}

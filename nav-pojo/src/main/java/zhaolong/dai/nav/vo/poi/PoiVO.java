package zhaolong.dai.nav.vo.poi;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zhaolong.dai.nav.vo.LocalPointVO;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "POI 信息")
public class PoiVO {

    @Schema(example = "1001")
    private Long id;

    private UUID sourceId;

    @Schema(example = "广州大学")
    private String name;

    private String context;

    private String category;

    private String basicCategory;

    private Double confidence;

    private String address;

    private LocalPointVO location;

    private Long dataVersion;

}

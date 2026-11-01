package zhaolong.dai.nav.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("poi")
public class PoiPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private UUID sourceId;

    private String name;

    private String context;

    private String category;

    private String basicCategory;

    private Double confidence;

    private String address;

    /*
     * 不直接映射 geom_local，
     * 空间坐标通过自定义 SQL 使用 ST_X/ST_Y 查询。
     */

    private String coordinateStatus;

    private String status;

    private Long dataVersion;

    /*
     * PostgreSQL timestamptz 推荐映射为 OffsetDateTime，
     * 不建议使用 LocalDateTime。
     */
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

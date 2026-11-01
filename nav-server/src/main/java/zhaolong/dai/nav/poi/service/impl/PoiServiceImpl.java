package zhaolong.dai.nav.poi.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zhaolong.dai.nav.cache.PoiDetailCache;
import zhaolong.dai.nav.constans.MessageConstants;
import zhaolong.dai.nav.dto.poi.PoiNearbyDTO;
import zhaolong.dai.nav.dto.poi.PoiKeywordDTO;
import zhaolong.dai.nav.dto.poi.PoiViewportDTO;
import zhaolong.dai.nav.exception.PoiNotFoundException;
import zhaolong.dai.nav.poi.mapper.PoiMapper;
import zhaolong.dai.nav.po.PoiPO;
import zhaolong.dai.nav.poi.mapper.model.PoiRow;
import zhaolong.dai.nav.poi.service.PoiService;
import zhaolong.dai.nav.poi.spatial.PoiCacheItem;
import zhaolong.dai.nav.poi.spatial.PoiSpatialBounds;
import zhaolong.dai.nav.poi.spatial.PoiSpatialQueryService;
import zhaolong.dai.nav.vo.LocalPointVO;
import zhaolong.dai.nav.vo.poi.PoiListVO;
import zhaolong.dai.nav.vo.poi.PoiVO;
import zhaolong.dai.nav.version.DatasetCode;
import zhaolong.dai.nav.version.service.DatasetRevisionService;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoiServiceImpl extends ServiceImpl<PoiMapper, PoiPO> implements PoiService {

    private final PoiMapper poiMapper;
    private final PoiSpatialQueryService poiSpatialQueryService;
    private final PoiDetailCache poiDetailCache;
    private final DatasetRevisionService datasetRevisionService;

    @Override
    public PoiVO getById(Long id) {
        log.info("根据 ID：{} 获取 POI", id);

        long version = datasetRevisionService.current(DatasetCode.POI);

        PoiDetailCache.LookupResult lookup = poiDetailCache.get(version, id);
        if (lookup.hit()) {
            log.debug("POI detail cache HIT: id={}, version={}", id, version);
            if (lookup.value() == null) {
                throw new PoiNotFoundException(MessageConstants.POI_NOT_FOUND_MSG);
            }
            return lookup.value();
        }

        log.debug("POI detail cache MISS: id={}, version={}", id, version);
        PoiRow row = poiMapper.selectDetailById(id);
        if (row == null) {
            poiDetailCache.putNotFound(version, id);
            throw new PoiNotFoundException(MessageConstants.POI_NOT_FOUND_MSG);
        }
        PoiVO result = convertRow(row);
        poiDetailCache.put(version, result);
        return result;
    }

    @Override
    public PoiListVO getByKeyword(PoiKeywordDTO query) {
        log.info(
            "搜索POI，keyword={}, limit={}",
            query.getKeyword(),
            query.getLimit()
        );

        String keyword = query.getNormalizedKeyword();

        List<PoiRow> rows = poiMapper.selectByKeyword(keyword, query.getLimit());
        List<PoiVO> items = convert(rows);

        return PoiListVO.builder()
                    .keyword(keyword)
                    .items(items)
                    .count(items.size())
                    .build();
    }

    @Override
    public PoiListVO getByViewport(PoiViewportDTO query) {
        Double[] viewport = query.getViewport();
        if (viewport == null
                || viewport.length != 4
                || java.util.Arrays.stream(viewport)
                    .anyMatch(value -> value == null || !Double.isFinite(value))
                || viewport[0] >= viewport[2]
                || viewport[1] >= viewport[3]) {
            throw new IllegalArgumentException(
                    "viewport 必须是 [minX, minY, maxX, maxY]，并满足 minX < maxX、minY < maxY"
            );
        }

        log.info("搜索 POI，viewport = {}", (Object) viewport);

        List<PoiVO> items = poiSpatialQueryService
                // 1. 先网格化查询视口范围内所有瓦片内的 POI 列表（包含缓存）
                .findCandidates(PoiSpatialBounds.fromViewport(viewport))
                // 2. 二次筛选，只保留视口范围内的 poi，用 id 进行排序、limit
                .stream()
                .filter(item -> insideViewport(item, viewport))
                .sorted(Comparator.comparing(PoiCacheItem::id))
                .limit(query.getLimit())
                .map(this::convertItem)
                .toList();

        return PoiListVO.builder()
                .bbox(viewport)
                .items(items)
                .count(items.size())
                .build();
    }

    @Override
    public PoiListVO getByNearby(PoiNearbyDTO query) {
        double x = query.getX();
        double y = query.getY();
        double radius = query.getRadius();

        log.info("搜索附近 POI，x = {}, y = {}, radius = {}", x, y, radius);

        double radiusSquared = query.getRadius() * query.getRadius();
        PoiSpatialBounds bounds = PoiSpatialBounds.around(x, y, radius);
        List<PoiVO> items = poiSpatialQueryService
                // 1. 先网格化查询半径范围 bounds 内所有瓦片内的 POI 列表（包括缓存）
                .findCandidates(bounds)
                // 2. 二次筛选，只保留圈形范围内的 POI 点，用 id 进行排序、limit
                .stream()
                .filter(item -> distanceSquared(item, x, y) <= radiusSquared)
                .sorted(
                        Comparator
                                .comparingDouble((PoiCacheItem item) -> distanceSquared(item, x, y))
                                .thenComparing(PoiCacheItem::id)
                )
                .limit(query.getLimit())
                .map(this::convertItem)
                .toList();

        return PoiListVO.builder()
                .x(query.getX())
                .y(query.getY())
                .radius(query.getRadius())
                .items(items)
                .count(items.size())
                .build();
    }

    private List<PoiVO> convert(List<PoiRow> rows) {
        return rows.stream().map(this::convertRow).toList();
    }

    private PoiVO convertRow(PoiRow row) {
        return PoiVO.builder()
                .id(row.getId())
                .sourceId(row.getSourceId())
                .name(row.getName())
                .context(row.getContext())
                .category(row.getCategory())
                .basicCategory(row.getBasicCategory())
                .confidence(row.getConfidence())
                .address(row.getAddress())
                .location(LocalPointVO.builder().x(row.getX()).y(row.getY()).build())
                .dataVersion(row.getDataVersion())
                .build();
    }

    private PoiVO convertItem(PoiCacheItem item) {
        return PoiVO.builder()
                .id(item.id())
                .sourceId(item.sourceId())
                .name(item.name())
                .context(item.context())
                .category(item.category())
                .basicCategory(item.basicCategory())
                .confidence(item.confidence())
                .address(item.address())
                .location(LocalPointVO.builder()
                        .x(item.x())
                        .y(item.y())
                        .build())
                .dataVersion(item.dataVersion())
                .build();
    }

    private boolean insideViewport(
            PoiCacheItem item,
            Double[] viewport
    ) {
        return item.x() >= viewport[0]
                && item.x() <= viewport[2]
                && item.y() >= viewport[1]
                && item.y() <= viewport[3];
    }

    private double distanceSquared(
            PoiCacheItem item,
            double x,
            double y
    ) {
        double dx = item.x() - x;
        double dy = item.y() - y;
        return dx * dx + dy * dy;
    }
}

# 后端 API Reference

## 1. 基本约定

### 服务地址与版本

本地默认地址：`http://localhost:8080`。当前业务 API 前缀为 `/api/v1`。

### Content-Type

- JSON 请求：`Content-Type: application/json`；
- JSON 响应：`application/json`；
- MVT 响应：`application/vnd.mapbox-vector-tile`。

### 坐标语义

当前广州数据中的 POI、道路、路线 WKT、地图范围和瓦片统一使用 Web Mercator
（`EPSG:3857`）平面坐标。服务不会把输入的经纬度自动投影到 EPSG:3857，调用方应
保证传入坐标与当前数据集一致。

- `x`、`y` 不是 longitude/latitude；
- `distanceLocal`、`radius`、`maxSnapDistanceLocal` 在当前广州数据集下以米为单位；
- `durationSeconds` 使用秒；
- `geometryWkt` 是 EPSG:3857 平面坐标 WKT，不可直接当作 EPSG:4326 经纬度数据。

### 统一 JSON 响应

除地图范围和 MVT 接口外，JSON API 使用：

```json
{
  "code": 1,
  "msg": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | `1` 成功，`0` 失败 |
| `msg` | string | 人类可读消息，不应用作稳定机器错误码 |
| `data` | any/null | 成功数据；失败时通常为空 |

HTTP 状态码仍表示请求结果，客户端不能只检查 `code`。

### 错误响应

```json
{
  "code": 0,
  "msg": "请求参数不合法",
  "data": null
}
```

| HTTP | 场景 |
| --- | --- |
| `400` | 参数校验失败、JSON 不可解析、路径点组合非法、游览约束非法 |
| `404` | POI 不存在、道路吸附范围内无可用道路、路线不可达 |
| `500` | 未分类服务端异常；响应不暴露内部堆栈。当前未知地图图层也会进入此分支，见 MVT 说明 |

## 2. 公共数据结构

### LocalPoint

```json
{ "x": 12614078.2, "y": 2648801.5 }
```

`x`、`y` 均为必填有限数值，并且必须与后端数据使用同一坐标空间。

### Waypoint

路径点必须且只能使用一种表达：

```json
{ "poiId": 1001 }
```

或：

```json
{ "point": { "x": 12614078.2, "y": 2648801.5 } }
```

### 枚举

| 字段 | 值 | 语义 |
| --- | --- | --- |
| `travelMode` | `WALKING` | 仅使用允许步行的道路成本 |
|  | `DRIVING` | 仅使用允许驾车的道路成本 |
| `strategy` | `FASTEST` | 以通行秒数作为优化目标 |
|  | `SHORTEST` | 以局部道路长度作为优化目标 |
| `algorithm` | `AUTO` | ≤12 点用 Held-Karp，否则 NN + 2-opt |
|  | `HELD_KARP` | 精确算法，最多 12 点 |
|  | `NN_TWO_OPT` | 最近邻初解 + 2-opt 启发式改进 |

### SnappedPoint

```json
{
  "edgeId": 251,
  "fraction": 0.374,
  "snappedPoint": { "x": 12614080.1, "y": 2648799.8 },
  "distanceLocal": 2.55,
  "networkVersion": 3
}
```

`fraction` 为吸附点在道路几何方向上的相对位置，范围 0～1。`networkVersion` 标识本次计算使用的
路网数据版本。

## 3. POI API

### 3.1 关键字查询

```http
GET /api/v1/pois?keyword=大学&limit=20
```

| 参数 | 必填 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `keyword` | 否 | 空 | 最长 100 字符；服务端去除首尾空白 |
| `limit` | 否 | 20 | 1～10000 |

```bash
curl --get "http://localhost:8080/api/v1/pois" \
  --data-urlencode "keyword=大学" \
  --data-urlencode "limit=20"
```

响应 `data`：

```json
{
  "keyword": "大学",
  "x": null,
  "y": null,
  "radius": null,
  "bbox": null,
  "items": [{
    "id": 1001,
    "sourceId": "58e7884f-80bf-44ab-9389-c223d1637506",
    "name": "广州大学",
    "context": "education",
    "category": "university",
    "basicCategory": "education",
    "confidence": 0.98,
    "address": null,
    "location": { "x": 12613762.1, "y": 2648460.3 },
    "dataVersion": 5
  }],
  "count": 1
}
```

### 3.2 附近查询

```http
GET /api/v1/pois/nearby?x=12614078.2&y=2648801.5&radius=500&limit=30
```

| 参数 | 必填 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `x`、`y` | 是 | - | 局部坐标 |
| `radius` | 是 | - | 正数，局部坐标单位 |
| `limit` | 否 | 20 | 1～10000 |

```bash
curl "http://localhost:8080/api/v1/pois/nearby?x=12614078.2&y=2648801.5&radius=500&limit=30"
```

结果按实际圆形距离过滤和排序。`data` 的 `x`、`y`、`radius` 回显查询条件。

### 3.3 视口查询

```http
GET /api/v1/pois/viewport?viewport=minX&viewport=minY&viewport=maxX&viewport=maxY&limit=1000
```

`viewport` 必须重复四次，顺序为 `[minX, minY, maxX, maxY]`，并满足
`minX < maxX`、`minY < maxY`。

```bash
curl "http://localhost:8080/api/v1/pois/viewport?viewport=12613000&viewport=2648000&viewport=12615000&viewport=2650000&limit=1000"
```

结果经过精确矩形范围过滤；`data.bbox` 回显视口。

### 3.4 POI 详情

```http
GET /api/v1/pois/{id}
```

```bash
curl "http://localhost:8080/api/v1/pois/1001"
```

存在时返回单个 Poi；不存在时返回 404。

### Poi 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | integer | 服务端主键 |
| `sourceId` | UUID/null | 源数据标识 |
| `name` | string | POI 名称 |
| `context` | string/null | 上下文分类信息 |
| `category` | string/null | 细分类 |
| `basicCategory` | string/null | 基础分类 |
| `confidence` | number/null | 源数据置信度 |
| `address` | string/null | 地址 |
| `location` | LocalPoint | 局部坐标 |
| `dataVersion` | integer/null | POI 数据版本 |

## 4. 路由 API

### 4.1 道路吸附

```http
POST /api/v1/routing/snap
```

```bash
curl -X POST "http://localhost:8080/api/v1/routing/snap" \
  -H "Content-Type: application/json" \
  -d '{
    "point": {"x": 12614078.2, "y": 2648801.5},
    "travelMode": "WALKING",
    "maxSnapDistanceLocal": 100.0
  }'
```

请求字段：

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `point` | 是 | - | 原始局部坐标 |
| `travelMode` | 否 | `WALKING` | 道路通行模式 |
| `maxSnapDistanceLocal` | 否 | 100.0 | 正数；范围内无道路时返回 404 |

响应 `data` 为 SnappedPoint。

### 4.2 两点路径规划

```http
POST /api/v1/routing/routes
```

POI 到临时坐标示例：

```bash
curl -X POST "http://localhost:8080/api/v1/routing/routes" \
  -H "Content-Type: application/json" \
  -d '{
    "origin": {"poiId": 1001},
    "destination": {"point": {"x": 12615000.0, "y": 2649100.0}},
    "travelMode": "DRIVING",
    "strategy": "FASTEST",
    "maxSnapDistanceLocal": 100.0
  }'
```

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `origin` | 是 | - | Waypoint |
| `destination` | 是 | - | Waypoint |
| `travelMode` | 否 | `WALKING` | 步行或驾车 |
| `strategy` | 否 | `FASTEST` | 最快或最短 |
| `maxSnapDistanceLocal` | 否 | 100.0 | 起终点各自的最大吸附距离 |

响应 `data`：

```json
{
  "travelMode": "DRIVING",
  "strategy": "FASTEST",
  "distanceLocal": 2850.4,
  "durationSeconds": 428.7,
  "geometryWkt": "LINESTRING(12614080.1 2648799.8,12614120.2 2648820.4)",
  "originSnap": {},
  "destinationSnap": {},
  "networkVersion": 3
}
```

`originSnap`、`destinationSnap` 使用完整 SnappedPoint 结构。路网不可达时返回 404。

### 4.3 多点游览规划

```http
POST /api/v1/routing/tours
```

闭环示例：

```bash
curl -X POST "http://localhost:8080/api/v1/routing/tours" \
  -H "Content-Type: application/json" \
  -d '{
    "stops": [
      {"poiId": 1001},
      {"poiId": 1002},
      {"point": {"x": 12615000.0, "y": 2649100.0}}
    ],
    "algorithm": "AUTO",
    "travelMode": "WALKING",
    "strategy": "SHORTEST",
    "roundTrip": true,
    "fixedStartIndex": 0,
    "maxSnapDistanceLocal": 100.0
  }'
```

| 字段 | 必填 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `stops` | 是 | - | 2～50 个 Waypoint |
| `algorithm` | 否 | `AUTO` | Held-Karp 最多 12 点 |
| `travelMode` | 否 | `WALKING` | 步行或驾车 |
| `strategy` | 否 | `FASTEST` | 最快或最短 |
| `roundTrip` | 否 | `true` | 是否回到固定起点 |
| `fixedStartIndex` | 否 | 0 | `stops` 的 0-based 下标 |
| `fixedEndIndex` | 否 | null | 仅开放路线可用；为空则自由选择终点 |
| `maxSnapDistanceLocal` | 否 | 100.0 | 每个停靠点的最大吸附距离 |

约束：

- `fixedStartIndex`、`fixedEndIndex` 必须在 stops 范围内；
- `roundTrip=true` 时不能指定固定终点；
- 同一个 Waypoint 不能同时给 `poiId` 和 `point`；
- 任一点吸附失败或成本矩阵不可达时，整个请求失败。

响应 `data`：

```json
{
  "visitOrder": [0, 2, 1, 0],
  "travelMode": "WALKING",
  "strategy": "SHORTEST",
  "roundTrip": true,
  "algorithm": "HELD_KARP",
  "optimal": true,
  "optimizedCost": 6350.2,
  "optimizedCostUnit": "LOCAL_DISTANCE",
  "totalDistanceLocal": 6350.2,
  "totalDurationSeconds": 5220.4,
  "legs": [{
    "fromStopIndex": 0,
    "toStopIndex": 2,
    "distanceLocal": 1800.1,
    "durationSeconds": 1500.0,
    "geometryWkt": "LINESTRING(...)"
  }],
  "snappedStops": [],
  "networkVersion": 3
}
```

- `visitOrder` 的值是请求 `stops` 的下标；闭环时起点在末尾重复；
- `algorithm` 是实际使用的算法；
- `optimal=true` 仅表示该约束下使用了保证精确解的算法；
- `optimizedCostUnit` 在 FASTEST 时为 `SECONDS`，SHORTEST 时为 `LOCAL_DISTANCE`；
- `legs` 按访问顺序提供每一段完整路线；
- `snappedStops` 与请求 stops 顺序对应，而不是访问顺序。

## 5. 地图与 MVT API

### 5.1 地图范围

```http
GET /api/v1/map/tiles/bounds
```

此接口直接返回对象，不使用统一 Result 包装：

```json
{
  "minX": 12607000.0,
  "minY": 2619000.0,
  "maxX": 12686000.0,
  "maxY": 2695000.0
}
```

```bash
curl "http://localhost:8080/api/v1/map/tiles/bounds"
```

### 5.2 矢量瓦片

```http
GET /api/v1/map/tiles/{layerCode}/{z}/{x}/{y}.mvt
```

`layerCode=base` 返回组合底图。其他值必须是数据库中启用的图层编码。

```bash
curl "http://localhost:8080/api/v1/map/tiles/base/10/803/415.mvt" \
  --output tile.mvt
```

成功响应：

- `Content-Type: application/vnd.mapbox-vector-tile`；
- `Cache-Control: public, max-age=3600`；
- Body 为 protobuf MVT 字节，不是 JSON；
- 无要素瓦片可以返回合法的空字节或空 MVT；
- 未知图层编码会抛出 `MapLayerNotFoundException`。当前全局异常映射尚未为它配置 404，因而实际返回
  500 和通用 JSON 错误；这是当前契约而非推荐设计，后续修复为 404 时属于需要同步记录的 API 行为变更。

当前组合底图可能包含这些 MVT source layer：`boundary`、`boundary_outline`、`landcover`、
`water`、`building`、`parking`、`road`。客户端应按实际瓦片中存在的命名图层容错。

## 6. 版本与缓存语义

- Poi 中的 `dataVersion` 表示 POI 数据版本；
- 路由响应中的 `networkVersion` 表示路网版本；
- Redis Key 包含缓存结构版本和数据版本；
- 数据版本变化后请求自然转向新 Key，旧 Key 等待 TTL 到期；
- MVT 同时使用服务端 Redis 缓存和一小时浏览器缓存头。

客户端不应自行拼接 Redis Key，也不应假定数据版本连续增长。


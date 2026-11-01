# Database migrations

`db/migration` 是数据库结构的唯一权威来源，由 Flyway 按版本顺序执行。

## 版本划分

- `V1`：启用 PostGIS 与 pgRouting。
- `V2`：创建路网和 POI 表、约束及索引。
- `V3`：创建地图图层与地图要素表。
- `V4`：创建数据集版本表和事务化递增函数。
- `V5`：初始化地图图层元数据。
- `V6`：创建局部瓦片、MVT 和 GeoJSON 导入函数。
- `V7`：清理旧数据库中的重复空间索引。
- `V8`：为 baseline 旧库补齐地图缩放范围约束。
- `V9`：初始化路网节点和道路边（643 个节点、945 条边）。
- `V10`：初始化 POI（6225 条）。
- `V11`：初始化底图要素（2935 条）。
- `V12`：扩展广州路网与 POI 字段，并建立 WGS84 / Web Mercator 双几何列。
- `V13`：将局部瓦片金字塔切换到覆盖广州数据的 EPSG:3857 范围。
- `V14`：离线生成低缩放道路 LOD，并让 MVT 按层级选择简化路网。
- `V15`：递增地图数据版本，使 V14 之前生成的 MVT 缓存立即失效。
- `V16`：按 OSM 道路合并低缩放级别的拓扑碎片，降低 MVT 要素数量。
- `V17`：按渲染网格和道路等级聚合低缩放道路，显著减少浏览器 Feature 和绘制调用。
- `V18`：按屏幕像素尺度简化并合并低缩放道路，只输出绘制所需属性，减少内部线段和顶点数量。
- `V19`：增加统一面底图、广州行政区和共享 LOD 表，并将新底图接入组合 MVT。

## 现有数据库

当前开发库是在 Flyway 引入前手工创建的，已经完成一次 V5 baseline。另一套同结构
旧库首次接入时，需要临时设置：

```properties
FLYWAY_BASELINE_ON_MIGRATE=true
```

首次启动会把既有结构记录为 V5，然后执行兼容的 V6～V11。V9～V11 先把参考数据
装入临时表，再按主键合并，因此不会重复插入开发库已有的数据。成功后应删除这个环境
变量。默认值为 `false`，防止错误地接纳结构未知的非空数据库。全新空库不需要开启
baseline，会从 V1 执行全部迁移。

## 数据和结构的边界

Flyway 管理扩展、表、约束、索引、函数，以及用于演示和首次运行的稳定参考数据。
因此空 PostgreSQL 实例在安装 PostGIS、pgRouting 后，只需启动应用，就能得到完整的
路网、POI 和底图数据。V9～V11 使用 PostgreSQL `COPY FROM STDIN`，既保留几何和
JSON 数据，又避免逐行 `INSERT` 的初始化开销。

V9～V11 是项目初始化快照，不是运行期的数据同步机制。部署后由用户新增或修改的
业务数据，应通过导入服务、管理接口或数据库备份管理。已经执行的 migration 不得
覆盖或改写；如果以后要发布一版新的内置数据，应新增更高版本的迁移，并明确采用
增量更新还是替换策略。

手工或批量写数据时，必须在同一个事务中递增对应数据集版本：

```sql
BEGIN;
-- INSERT / UPDATE / DELETE ...
SELECT public.bump_dataset_revision('poi');
COMMIT;
```

`import_local_geojson()` 已经自动递增 `map` 数据集版本。

## 广州数据导入

广州原始库不是 Flyway 管理的数据源，约 60 万条道路边也不应固化进版本脚本。
V12～V13 只管理结构；迁移完成后使用独立导入器从 `nav_map_db` 流式导入：

```powershell
$env:GUANGZHOU_IMPORT_CONFIRM='replace'
mvn -pl nav-server -am compile
mvn -pl nav-server org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
  -Dexec.mainClass=zhaolong.dai.nav.importer.GuangzhouDataImporter `
  -Dexec.classpathScope=runtime
```

导入器默认从 `DATABASE_URL` 推导同实例的 `nav_map_db`，也可以通过
`SOURCE_DATABASE_URL`、`SOURCE_DATABASE_USERNAME` 和 `SOURCE_DATABASE_PASSWORD`
覆盖源连接。首次运行会把原数据保存在 `migration_backup` schema，然后在单个事务内
替换 `road_node`、`road_edge` 和 `poi`，跳过不符合现有约束的自环边，更新序列与
数据集版本，并在失败时整体回滚。

导入完成后会调用 `refresh_road_edge_lod()`：z2～z3 使用 8192 米简化结果，z4～z5
使用 2048 米简化结果，z6～z7 使用 512 米简化结果，z8 以上使用原始道路。各级别还会
按照道路等级设置最小显示层级，LOD 数据只参与地图显示，不参与吸附和路径规划。

## Geofabrik 广州底图导入

V19 使用 `base_surface_feature` 统一管理 `landcover`、`water`、`building` 和
`parking`，行政区单独存入 `admin_area`，所有主题共用 `base_map_lod`，避免按每个
源图层建立业务表。迁移到 V19 后执行：

```powershell
F:\SoftwareTools\Dev\Miniconda3\envs\DeepLearning\python.exe `
  scripts\import_geofabrik_basemap.py --replace
```

脚本使用 `guangzhou_area.json` 的 11 个区裁剪 GeoPackage；具名的面 POI、宗教、
交通和非停车交通设施会以 `PointOnSurface` 合并到 `poi`，`natural` 不导入。导入、
共享 LOD 重建和数据集版本递增位于同一个事务，失败时整体回滚。

V20 提前了大面积水面、土地覆盖、建筑物和停车区域的显示层级，并把行政边界改为
“先简化面、再取边界”，从而保证低 zoom 的区界仍为闭合有效环。V20 还降低了全市
视图的道路密度；V21 让 z8 以上的原始道路同样遵守道路等级的最小显示层级，避免从
LOD 切换到原始几何时突然出现全量小路。

V22 将行政区改为真正的 Polygon 底面，并在数据库端把所有显示道路裁剪到 11 个区的
合并范围内。`GET /api/v1/map/tiles/bounds` 返回行政区在 EPSG:3857 下的 bbox，前端用
它加 5% 缓冲作为初始视图，不再以整个局部瓦片金字塔作为启动范围。

V23 取消行政区几何的全部简化：6 个显示层级均直接复用 `admin_area.geom_local` 原始面，
避免区界在 LOD 切换时产生形变或断裂。

V24 进一步取消全部面状要素的简化、合并和分级几何切换，土地覆盖、水面、建筑、
停车场和行政区在所有 zoom 都直接读取权威 `geom_local`。行政面仅填充，原始行政边界
作为独立线图层绘制，避免 MVT 裁剪边被误描成瓦片方格。V25 将低 zoom 道路对行政区
的裁剪移到离线 LOD 刷新阶段，在线瓦片不再重复执行大线段求交。

首次初始化时 Redis 为空，因此种子迁移不递增数据集版本；各数据集从版本 1 开始。

## 路由 cost

驾车 `FASTEST` 的正反向 cost 统一使用秒。查询时优先按对应方向的
`maxspeed_forward` / `maxspeed_backward` 和 `length_m` 动态计算；方向限速不可用时，
先尝试旧数据的通用 `speed_kmh`，最后回退到导入的 `driving_cost_s` /
`driving_reverse_s`。静态耗时字段的正负仍是方向是否可通行的权威依据，因而动态限速
不会放开原本禁行的方向。`SHORTEST` 仍按米优化，但返回的预计耗时采用同一套速度与
静态兜底规则。

## 旧脚本

`db/create_*.sql` 是引入 Flyway 前的手工脚本，仅保留作历史参考；不要再把它们
用于新环境初始化，也不要修改已经成功执行过的 Flyway migration。

# 项目成果与可复现实证

更新时间：2026-11-01（Asia/Shanghai）

## 1. 项目解决什么问题

该项目把广州 OSM、POI、行政区和底图数据整理到一套可离线部署的空间数据库中，
并围绕“地图展示、地点检索、道路吸附、路线规划、多点游览”提供完整 WebGIS 链路。
后端负责空间计算和 MVT 编码，前端使用自研 TypeScript/Canvas 2D 渲染内核完成瓦片
调度、交互和路线可视化。

```text
广州 OSM / POI / 行政区数据
          │
          ▼
PostgreSQL + PostGIS + pgRouting
          │
          ├── POI 空间检索与 GiST 索引
          ├── 道路吸附、路径与成本矩阵
          ├── 道路 LOD 与 MVT 编码
          └── 数据版本与缓存一致性
          │
          ▼
Spring Boot + MyBatis + Redis
          │
          ▼
TypeScript + Canvas 2D WebGIS
```

## 2. 可直接展示的功能

- 广州市域组合矢量底图：行政区、土地覆盖、水面、建筑、停车区域和道路；
- POI 关键字、附近、视口和详情查询；
- POI 或地图坐标到可通行道路的最近点吸附；
- 步行/驾车、最快/最短两点路径；
- 2～50 个停靠点的访问顺序规划，小规模精确求解、大规模启发式优化；
- 地图点选、POI 聚类、图层控制、路线结果和多段游览展示；
- Docker Compose 启动 PostgreSQL/PostGIS/pgRouting、Redis 与后端服务。

## 3. 核心技术决策

### 空间查询不是只靠缓存

PostGIS 使用 GiST、`ST_DWithin` 和 KNN 获得空间候选；Redis 网格缓存只负责复用候选
POI，最终仍按真实圆形或视口边界精确过滤。这样可以避免矩形包围盒把半径外的点误返回。

当前默认网格为 `z=9`。在同一实现的层级对照中，500 m 热缓存 P50 为 1.52 ms，
数据库基线为 4.99 ms；2,000 m 热缓存 P50 为 13.40 ms，数据库基线为 22.58 ms。
2,000 m 查询会读取 72 个网格 Key，因此该选择是延迟、Key 数和内存之间的工程折中，
不是对所有查询范围都保证同样收益。

### 缓存失效使用数据版本而不是扫描 Redis

POI 和 MVT 缓存键同时包含缓存结构版本与业务数据版本。导入或更新数据时，数据库事务
递增 `dataset_revision`；后续请求自然切换到新 Key，旧 Key 由 TTL 回收，无需使用
`KEYS`、`SCAN` 或批量删除，也避免数据库提交成功但缓存删除失败的双写窗口。

### 路径起终点使用虚拟点

道路吸附返回道路 ID 和 fraction。路线计算通过 `pgr_withPoints` 从道路中间真实出发和
到达，而不是粗略替换为道路端点；单行方向及反向成本由 pgRouting 统一处理。

### 低层级道路采用离线 LOD

z2～z7 不在每次请求中直接处理 62.8 万条道路边，而是读取离线完成道路聚合、几何
简化和行政区预裁剪的 LOD 表。z8 及以上按设计切回原始道路，以兼顾低层级性能和高
层级细节。

### 多点规划区分“顺序优化”和“完整接口”

Held-Karp 只负责访问顺序，不把成本矩阵和每段几何生成时间算作算法耗时。10 个可达
游览点下，成本矩阵约 1.42 s，Held-Karp 顺序优化 P50 约 0.41 ms，而包含 10 段路线
几何的完整规划约 10.35 s。该拆分可以准确定位性能瓶颈。

## 4. 量化证据

| 专项 | 数据与方法 | 结果 |
| --- | --- | --- |
| 数据规模 | 当前本机证据库 active 记录 | 34,370 POI；628,609 道路边 |
| GiST 范围查询 | 同机同库、500 m、7 次稳态中位数 | 77.515 ms → 0.056 ms，执行计划由 Seq Scan 切换为 Index/Bitmap Scan |
| 道路 LOD | z2～z7、18 个密集瓦片、每分支 126 次样本 | P50 409.46 ms → 2.30 ms；体积减少 91.2%；顶点减少 83.0% |
| POI 并发冷缓存 | 单 JVM、8 并发、50 轮、400 请求 | 数据库回源 50 次，相对每请求回源减少 87.5% |
| MVT Redis | 同一非空瓦片连续两次观测 | 249.07 ms → 5.80 ms；单样本仅作为缓存链路证据 |
| HTTP 混合负载 | JMeter 本机短时测试，1,007 个样本 | 0 错误；约 142.4 请求/秒；读取请求总体 P95 57 ms |
| 前端空间索引 | 10 万点、连续 100 次固定视口查询 | 累计 P50 约 200 ms → 0.88 ms |
| Canvas 绘制 | Headless Chrome、1200×800、600 帧 | 平均约 1.35 ms → 0.15 ms；P95 约 1.9 ms → 0.3 ms |

所有数据均来自本机开发环境，不代表生产容量上限。完整方法、样本数与限制分别见：

- [后端简历指标证据](resume-testing-evidence.md)
- [后端测试报告](test-report-2026-10-31.md)
- 前端仓库 `docs/frontend-testing-evidence.md`
- JMeter HTML Dashboard：`reports/jmeter/latest/html/index.html`

## 5. 自动化验证状态

2026-11-01 最新回归：

- `mvn -pl nav-server -am test`：发现 45 项，40 项执行通过，0 失败、0 错误，5 项真实
  数据库证据测试默认跳过；
- `ResumeEvidenceIntegrationTest` 显式启用后通过，实测 34,370 POI、628,609 道路边；
- 前端 `npm run test:run`：4 个文件、13 项测试全部通过；
- 前端 `npm run build`：TypeScript 检查和 Vite 生产构建通过，产物已同步到后端静态目录；
- Flyway：26 条 migration 校验通过，当前 schema 版本 V25。

真实环境证据测试默认关闭，是为了避免普通 CI 或没有 PostGIS/Redis 的开发机误执行
约一分钟以上的性能测试。它们可以通过系统属性单独启用。

## 6. 快速复现

```powershell
Copy-Item .env.example .env
docker compose up --build -d
mvn -pl nav-server -am test
powershell -ExecutionPolicy Bypass -File scripts\run_resume_evidence.ps1
```

启动完成后：

- 导航页面：`http://localhost:8080`
- Scalar API 文档：`http://localhost:8080/scalar`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

## 7. 已知边界

- 当前 POI 数量取决于导入批次和清洗规则；文档中的 34,370 是当前可复现实证库的
  active 数量，不将原始文件行数直接当作有效入库数量；
- 完整多点游览接口的主要瓶颈仍是成本矩阵和逐段 pgRouting 几何生成；
- POI 冷请求合并锁是单 JVM 对象锁，多实例部署需要分布式协调或请求合并策略；
- JMeter 报告是短时同机基线，不包含长稳、峰值、跨网络和资源饱和点；
- 前端基准测量核心查询与 Canvas 绘制，不等同于整页 FPS 或端到端首屏时间。

这些边界被保留在公开文档中，使性能结论可解释、可追溯，也便于继续演进。

# 导航地图系统后端测试报告

JMeter 与完整专项基准日期：2026-10-31

最新功能回归日期：2026-11-01

测试对象：`nav-server` 本地开发版本

测试范围：API 控制器、参数校验、异常契约、核心服务与算法、真实 PostGIS/Redis 专项基准、JMeter 本机负载基线

## 1. 结论摘要

- Maven 完整测试共发现 45 项：40 项执行通过，0 失败，0 错误，5 项真实环境证据测试默认跳过；
- 新增 17 项 MockMvc API 测试，覆盖全部 9 个业务 API 路径的主要成功与失败契约；
- JMeter 运行 1,007 个真实 HTTP 样本，错误数为 0，错误率 0%；
- JMeter Dashboard 统计总体吞吐量约 142.4 请求/秒；
- 三类 POI 查询各 200 个样本，P95 为 56～59 ms；
- Redis 命中的 MVT 瓦片共 200 个样本，P95 为 7 ms；
- 两点路径 6 个样本平均 1,834.7 ms；三点游览单次 6,869 ms，样本量不足以形成稳定分位数结论；
- 已确认一个现有 API 契约问题：未知地图图层返回 HTTP 500，建议后续映射为 404。

本轮结果证明测试配置下的功能正确性和短时本机负载基线，不代表生产容量上限。

### 1.1 2026-11-01 最新回归

在广州前端 UI、导航路线渲染和 POI 网格默认层级调整为 `z=9` 后，重新执行：

```powershell
mvn -pl nav-server -am test
```

结果仍为 45 项测试、40 项执行通过、0 失败、0 错误、5 项证据测试默认跳过；Spring
上下文成功连接 PostgreSQL 17.5，Flyway 26 条 migration 校验通过，schema 保持 V25。

同时显式启用 `ResumeEvidenceIntegrationTest`，得到：

| 指标 | 2026-11-01 回归结果 |
| --- | ---: |
| 有效 POI | 34,370 |
| 有效道路边 | 628,609 |
| GiST 查询 | 0.036 ms |
| 强制顺序扫描 | 71.171 ms |
| 10 点成本矩阵 | 1,402.048 ms |
| Held-Karp 顺序优化中位数 | 0.295 ms |
| 完整 10 点游览规划中位数 | 10,140.463 ms |

前端同步执行 `npm run test:run` 和 `npm run build`：4 个测试文件、13 项测试全部通过，
TypeScript 检查与 Vite 生产构建通过，最新静态产物已写入后端资源目录。

本节是功能与关键证据回归；第 5～7 节的 JMeter、LOD 和网格并发数字仍来自
2026-10-31 原始基准，没有把旧结果标记成 2026-11-01 重测。

## 2. 测试环境

| 项目 | 环境 |
| --- | --- |
| 操作系统 | Windows 10 专业版 |
| CPU | Intel Core i5-13490F，10 核 / 16 逻辑处理器 |
| 内存 | 31.8 GB |
| Java | 21.0.7 |
| Spring Boot | 4.1.1 |
| PostgreSQL | 17.5，PostGIS / pgRouting |
| Redis | 本机实例，业务配置数据库 0 |
| JMeter | 5.6.3，非 GUI 模式 |
| 部署方式 | JMeter、后端、PostgreSQL、Redis 位于同一台开发机 |

同机测试消除了外部网络波动，但也会产生客户端和服务端争用同一 CPU 的影响，因此绝对数值只适合作为本机基线。

## 3. API 单元测试

测试使用 MockMvc 独立加载 Controller、Hibernate Validator 和 `GlobalExceptionHandler`，业务 Service 使用 Mockito 隔离。它验证 HTTP 绑定、JSON 序列化、参数校验、状态码和异常响应，不访问真实数据库或 Redis。

### 3.1 POI API

| 用例 | 接口/场景 | 预期 | 结果 |
| --- | --- | --- | --- |
| POI-01 | `GET /api/v1/pois` 关键字查询 | 200、统一成功响应、参数正确传给 Service | 通过 |
| POI-02 | `GET /api/v1/pois/nearby` 附近查询 | 200、查询条件和结果正确序列化 | 通过 |
| POI-03 | `GET /api/v1/pois/viewport` 视口查询 | 200、四元 bbox 正确绑定 | 通过 |
| POI-04 | 附近查询缺少 y | 400、`code=0`，Service 不执行 | 通过 |
| POI-05 | `GET /api/v1/pois/{id}` 正常详情 | 200、POI 与局部坐标正确序列化 | 通过 |
| POI-06 | POI 不存在 | 404、隐藏内部实现并返回业务消息 | 通过 |

### 3.2 路由 API

| 用例 | 接口/场景 | 预期 | 结果 |
| --- | --- | --- | --- |
| ROUTE-01 | `POST /routing/snap` 道路吸附 | 200、吸附边和版本正确返回 | 通过 |
| ROUTE-02 | `POST /routing/routes` 两点路径 | 200、距离、模式及 WKT 正确返回 | 通过 |
| ROUTE-03 | `POST /routing/tours` 多点游览 | 200、访问顺序和实际算法正确返回 | 通过 |
| ROUTE-04 | 两点路径缺少终点 | 400、终点必填消息，Service 不执行 | 通过 |
| ROUTE-05 | JSON 无法解析 | 400、统一“请求参数不合法” | 通过 |
| ROUTE-06 | 游览点少于 2 个 | 400、游览点数量校验消息 | 通过 |
| ROUTE-07 | 两点不可达 | 404、`code=0` 和不可达消息 | 通过 |

### 3.3 地图 API

| 用例 | 接口/场景 | 预期 | 结果 |
| --- | --- | --- | --- |
| MAP-01 | `GET /map/tiles/bounds` | 200、行政区四至范围正确序列化 | 通过 |
| MAP-02 | `GET /map/tiles/{layer}/{z}/{x}/{y}.mvt` | MVT Content-Type、长度和一小时公共缓存头 | 通过 |
| MAP-03 | 非法 zoom | 400、瓦片层级错误消息 | 通过 |
| MAP-04 | 未知图层 | 当前契约为 500 和通用错误，内部异常不泄漏 | 通过，记录为缺陷 |

测试源码：

- `nav-server/src/test/java/zhaolong/dai/nav/api/PoiControllerTest.java`
- `nav-server/src/test/java/zhaolong/dai/nav/api/RoutingControllerTest.java`
- `nav-server/src/test/java/zhaolong/dai/nav/api/VectorTileControllerTest.java`

## 4. Maven 完整测试结果

执行命令：

```powershell
mvn -pl nav-server -am test
```

| 指标 | 数量 |
| --- | ---: |
| 测试发现 | 45 |
| 执行通过 | 40 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 5 |

5 项跳过测试是连接真实 PostGIS/Redis 的证据基准，必须通过 `resume.evidence`、`poi.cache.evidence` 或 `lod.evidence` 系统属性显式开启，以避免普通 CI 误运行性能测试。

除 17 项 API 测试外，常规套件还覆盖：POI Service、空间网格索引、缓存回源合并、MVT Cache Aside、Held-Karp、最近邻 + 2-opt、算法选择器和 Spring 上下文启动。

## 5. JMeter 场景设计

测试计划：`performance/jmeter/nav-api-load-test.jmx`

| 线程组 | 并发线程 | Ramp-up | 循环 | 每循环请求 | 样本数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 高频读取 API | 10 | 5 s | 20 | 5 | 1,000 |
| 两点路径 | 2 | 2 s | 3 | 1 | 6 |
| 三点游览 | 1 | 1 s | 1 | 1 | 1 |
| 合计 | - | - | - | - | 1,007 |

高频读取包含关键字 POI、附近 POI、视口 POI、地图范围和同一已缓存 MVT。JSON API 增加 `code=1` 响应断言；HTTP 错误、断言失败和业务失败都会计入 JMeter 错误率。

两点路径使用 POI 97→26200；三点游览使用已验证完全可达的 POI 6769、10714、23310。路径请求重复使用相同参数，因此结果包含服务端缓存收益，不能解释为冷路径规划耗时。

## 6. JMeter 测试结果

| 请求 | 样本 | 错误率 | 平均 | P50 | P90 | P95 | P99 | 最大 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| POI 关键字查询 | 200 | 0% | 45.7 ms | 46 ms | 54 ms | 56 ms | 69 ms | 87 ms |
| POI 附近查询 | 200 | 0% | 45.1 ms | 44 ms | 52 ms | 58 ms | 71 ms | 256 ms |
| POI 视口查询 | 200 | 0% | 45.7 ms | 46 ms | 57 ms | 59 ms | 91 ms | 115 ms |
| 地图范围 | 200 | 0% | 6.3 ms | 2 ms | 5 ms | 10 ms | 214 ms | 235 ms |
| Redis 命中 MVT | 200 | 0% | 3.4 ms | 2 ms | 5 ms | 7 ms | 19 ms | 40 ms |
| 两点步行路径 | 6 | 0% | 1,834.7 ms | 1,717 ms | 2,250 ms | 2,250 ms | 2,250 ms | 2,250 ms |
| 三点步行游览 | 1 | 0% | 6,869 ms | - | - | - | - | 6,869 ms |

JMeter Dashboard 汇总：

- 总样本：1,007；
- 错误：0；
- 错误率：0%；
- 平均响应时间：46.8 ms；
- P90：52 ms；
- P95：57 ms；
- P99：约 233.7 ms；
- 吞吐量：约 142.4 请求/秒。

总体分位数主要由 1,000 个读取请求决定，不能用总体 P95 代表路径规划接口。路径和游览必须分别解读。

## 7. 已完成的专项真实环境测试

| 专项 | 结果摘要 |
| --- | --- |
| POI GiST | 500 m 查询首次观测 69.7→1.5 ms，约 47 倍；稳态受数据库缓存影响更大 |
| POI 并发冷缓存 | 单实例同网格 50 轮、400 请求共 50 次数据库回源，回源减少 87.5% |
| 道路 LOD | z2～z7、18 个密集瓦片，MVT P50 409.5→2.3 ms，体积减少 91.2%，顶点减少 83.0% |
| MVT Redis | 单个非空瓦片首次观测 249 ms、重复请求 5.8 ms；单样本仅作功能证据 |
| 10 点路径优化 | 成本矩阵约 1.42 s，Held-Karp 顺序求解 P50 0.41 ms；完整接口约 10.35 s |

详细方法见 `docs/resume-testing-evidence.md`。

## 8. 已知问题与测试限制

1. `MapLayerNotFoundException` 没有专用异常映射，未知图层当前返回 500；建议后续改为 404 并同步 API 文档和测试。
2. `TourRequestDTO.stops` 在容器字段上使用 `@Valid`，Hibernate Validator 给出弃用警告；可改为 `List<@Valid WaypointDTO>`。
3. Mockito 当前通过动态 Java Agent 启用 inline mock maker；未来 JDK 可能禁止动态加载，应按 Mockito 建议配置测试 Agent。
4. JMeter 为约 7 秒的短时本机混合负载，不是容量、峰值、长稳或疲劳测试。
5. 高频读取使用固定参数，Redis、数据库页缓存和应用缓存均处于稳态；没有测量冷启动或缓存雪崩。
6. 两点路径只有 6 个样本，三点游览只有 1 个样本，不应在简历中写成稳定 P95/P99。
7. 本轮没有采集 CPU、堆内存、GC、数据库连接池和 Redis 命中率，无法给出资源饱和点。
8. 没有执行多实例、分布式锁或跨网络压测。
9. 本报告没有生成 JaCoCo 行覆盖率；“覆盖全部 API 路径”指契约场景覆盖，不等同于代码行覆盖率。

## 9. 报告产物与复现

运行 JMeter 并重新生成报告：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run_jmeter_report.ps1
```

可覆盖并发参数：

```powershell
.\scripts\run_jmeter_report.ps1 `
  -ReadThreads 20 -ReadLoops 50 `
  -RouteThreads 4 -RouteLoops 5 `
  -TourThreads 1 -TourLoops 2
```

产物：

- `reports/jmeter/latest/html/index.html`：JMeter HTML Dashboard；
- `reports/jmeter/latest/results.jtl`：全部原始样本；
- `reports/jmeter/latest/summary.csv`：逐接口 P50/P90/P95/P99 汇总；
- `reports/jmeter/latest/jmeter.log`：JMeter 执行日志。

每次运行会先清理项目内固定目录 `reports/jmeter/latest`，然后生成最新报告；不会删除项目外文件。

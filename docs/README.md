# 导航空间服务文档

本目录描述 `nav-map-sys` 后端的 HTTP 契约、系统架构和模块边界。

## 文档入口

- [项目成果与可复现实证](project-showcase.md)：面向项目评审者的功能、技术决策、量化结果、验证状态与已知边界总览。
- [API Reference](api-reference.md)：请求参数、响应字段、错误、curl 示例和 MVT 约定。
- [架构与模块](architecture.md)：系统分层、POI 缓存、路由、多点规划、MVT、数据版本和数据库迁移。
- [后端测试报告](test-report-2026-10-31.md)：保留 2026-10-31 JMeter 原始基线，并附 2026-11-01 最新回归结果。
- [简历指标测试证据](resume-testing-evidence.md)：GiST、MVT、道路 LOD、POI 网格缓存与多点规划的测试口径。
- [数据库迁移约定](../nav-server/src/main/resources/db/README.md)：Flyway 文件和数据导入维护规则。

运行服务后还可以访问机器可读和交互式文档：

- Scalar：<http://localhost:8080/scalar>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

Markdown 文档解释跨接口约定、架构语义和完整案例；OpenAPI 是请求/响应 Schema 的运行时来源。
修改 Controller、DTO 或 VO 时，应同时更新注解和本文档。


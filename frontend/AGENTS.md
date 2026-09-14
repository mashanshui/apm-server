# 前端协作规则

本文件适用于 `frontend/` 及其子目录，与[根目录规则](../AGENTS.md)共同生效。共用开发原则、注释要求、提交规范、安全和 SSH 约定由根文件统一维护。下文工程路径相对 `frontend/`。

## 项目结构与职责

前端使用 Vue 3、TypeScript、Vite、Vue Router、Pinia 和 ECharts；实际依赖以 `package.json` 和 `package-lock.json` 为准。

- `src/views/`：路由页面、组件组合与页面状态。
- `src/components/`：复用组件、图表和表格。
- `src/composables/`：查询编排、取消旧请求和分页状态。
- `src/stores/`：会话与当前应用等共享状态。
- `src/api/`：HTTP 客户端、参数编码、响应解析与错误处理。
- `src/types/`：与后端 JSON 契约对应的类型。
- `src/router/`：路由表与认证守卫。
- `src/utils/`：查询参数、格式化等工具。

## 编码与接口边界

遵循现有 Vue 和 TypeScript 代码风格，使用 UTF-8 编码，清理无用导入；方法和变量注释遵循根规则。复用已有组件、语义样式和分层，避免在页面中重复实现 HTTP 客户端或全局状态管理。

前端只通过 HTTP API 访问数据，不直连数据库。统一复用 `src/api/http.ts` 的 Cookie、CSRF 和认证错误处理；认证与应用状态保持现有运行时内存管理方式。

接口类型和统计口径以[正式 API 文档](../docs/api/README.md)为准。保留合法零值与缺失值的区别；筛选或应用切换时处理旧请求，避免旧响应覆盖新状态。

## 构建、测试与本地开发

以下命令在 `frontend/` 目录执行，脚本定义以 `package.json` 为准：

- `npm ci`：按锁文件安装依赖。
- `npm run dev`：启动 Vite 开发服务。
- `npm run typecheck`：执行 Vue 和 TypeScript 类型检查。
- `npm test`：执行 Vitest 测试。
- `npm run build`：类型检查并生成生产构建。
- `npm run preview`：预览构建产物。

前端代码改动后运行 `npm test` 和 `npm run typecheck`；涉及构建配置、依赖或发布时执行 `npm run build`。测试使用 Vitest 和 Vue Test Utils，沿用现有 `*.test.ts` 组织方式，覆盖正常路径、空数据、错误状态和关键交互。

Dashboard 可见变化应检查页面并附截图。分别说明自动化测试、模拟 API 页面检查和真实后端联调结果，构建成功不代表浏览器端到端验收通过。

## 文档维护

开发前阅读[平台知识库](../docs/knowledge-base/README.md)与[前端知识库](docs/knowledge-base/README.md)及相关主题页。页面、路由、状态、联调、测试和部署边界变化同步前端知识库。

平台架构仍由根知识库维护，正式接口契约仍由根目录 API 文档维护；影响客户端接入时同步[客户端接入文档](../docs/client-integration/README.md)。按根规则检查其他受影响文档，避免复制完整契约。

# Crash 前端

这是 Android APM 的 Crash、卡顿和内存分析前端，使用 Vue 3、TypeScript、Vite 和 ECharts。应用设置还提供只读 Agent 查询 Token 管理。架构、页面、API 消费、联调、测试、部署与维护约定统一记录在[前端知识库](docs/knowledge-base/README.md)。

## 快速启动

在仓库根目录执行：

```bash
./scripts/start-dev.sh
```

浏览器访问 <http://127.0.0.1:8088/login>，使用引导管理员登录后进入应用列表，再创建或选择应用查看 Crash 数据。停止本地服务：

```bash
./scripts/stop-dev.sh
```

需要重新构建后端和前端产物时：

```bash
./scripts/start-dev.sh --build
```

依赖安装、分别启动、固定数据准备和常见问题见[本地开发与联调](docs/knowledge-base/05-本地开发与联调.md)。

## 常用验证

在 `frontend/` 目录执行：

```bash
npm run typecheck
npm test
npm run build
```

测试范围和验收边界见[测试与质量保障](docs/knowledge-base/06-测试与质量保障.md)。

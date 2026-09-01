# Crash 前端

这是 Android APM 的 JVM Crash 分析前端，使用 Vue 3、TypeScript、Vite 和 ECharts。架构、页面、API 消费、联调、测试、部署与维护约定统一记录在[前端知识库](docs/knowledge-base/README.md)。

## 快速启动

在仓库根目录执行：

```powershell
.\scripts\start-dev.ps1
```

浏览器访问 <http://127.0.0.1:5173/login>，使用引导管理员登录后进入项目列表，再创建或选择项目查看 Crash 数据。停止本地服务：

```powershell
.\scripts\stop-dev.ps1
```

不使用 ClickHouse 时可以启动内存联调模式：

```powershell
.\scripts\start-dev.ps1 -StorageMode memory
```

依赖安装、分别启动、固定数据准备和常见问题见[本地开发与联调](docs/knowledge-base/05-本地开发与联调.md)。

## 常用验证

在 `frontend/` 目录执行：

```powershell
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
```

测试范围和验收边界见[测试与质量保障](docs/knowledge-base/06-测试与质量保障.md)。

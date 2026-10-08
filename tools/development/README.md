# Windows 启动与开发工具

在项目根目录双击 `启动农心.bat`，或使用以下命令。无需预装 Node、JDK、Maven，不修改系统 PATH，不要求管理员权限。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/development/dev.ps1 -Action Start
```

首次运行需要联网、约 2 GB 可用空间。工具版本、官方下载地址、架构与校验值固定在 `toolchain.json`；下载校验通过后才启用工具，重复启动复用 `.runtime`。Windows x64 已做开发环境回归，ARM64 尚未取得实机结果。

| 命令 | 作用 |
| --- | --- |
| `-Action Prepare` | 仅准备私有工具环境 |
| `-Action Start` | 安装前端依赖、构建后端、启动服务并打开浏览器 |
| `-Action Start -Mobile` | 前端开放到可信局域网，微信管理接口仍限本机 |
| `-Action Start -NoBrowser` | 启动但不自动打开浏览器 |
| `-Action Stop` | 停止本目录的受控启动器及其服务 |
| `-Action Check` | 私有工具执行后端、前端及启动器检查，不使用真实模型密钥 |
| `-Action Share -ProgramOnly` | 生成仅程序源码包：包含未提交的新源码、README、启动工具和必需运行资源，排除历史资料、赛题与 AI 记忆 |
| `-Action Share` | 生成较完整的源码与工程文档包；不是本次 GitHub 发布范围 |

## 日常使用

1. 下载并解压完整源码，路径可含中文和空格。
2. 双击根目录启动入口，等待显示服务就绪，再访问 `http://localhost:3000`。
3. 在网页“模型设置”填写自己的供应商、模型及 API Key。不要复制他人的浏览器配置或微信授权。
4. 修改 `frontend/src` 可热更新；Java 修改后需停止并重新启动。
5. 保持启动窗口打开。Ctrl+C、关闭窗口或启动失败时，Windows Job Object 会回收本次服务。

默认前端 3000、后端 8080。端口冲突时停止并报错，不自动换端口，也不结束无关程序。后端固定从 `server` 运行，本机数据库、上传及备份位于 `server/data`，启动工具不迁移或清空它们。

## 下载受限或启动失败

- 手动下载 `toolchain.json` 中当前架构的官方压缩包，按 `filename` 原名放入 `.runtime/downloads`，再启动；仍须通过清单校验。
- 不要把其他版本的压缩包改名冒充指定版本。校验失败或残缺环境不会被启用。
- 下载、解压、依赖安装和编译的日志在 `.runtime/logs`；运行日志在 `server/data`。分享排查信息前先脱敏，不上传整个本机目录。
- 重启服务前先正常停止旧启动窗口，避免端口占用及后端 JAR 文件锁。

## 配置与发布安全

可复制根目录 `.env.example` 为 `.env.local`，填写本机配置；已存在的进程环境变量优先。配置解析不执行脚本，密钥不进入启动器日志。

源码包输出至 `artifacts/releases`，不自动提交或推送。运行环境、依赖、密钥、数据库、上传、日志、IDE 配置和临时产物不会进入分享包。内置农技索引与城市表是程序运行资源，不是用户数据库或历史采集材料。

# 农心 Agent · NS-Agent

面向农业问答与农事管理的本机智能体原型。前端 React + TypeScript，后端 Java 21 + Spring Boot，使用 Spring JDBC + SQLite；支持自选模型、资料检索、人工确认的农事任务及本人微信聊天与提醒。

不把 AI 建议当作已经执行的农事，不编造用户田块或天气；药肥处方、病害判断等关键决策仍需核对现场条件与有效登记标签。

## 快速启动（Windows）

1. 下载本仓库完整源码并解压。
2. 双击根目录 **`启动农心.bat`**，首次启动需联网并预留至少 2 GB 空间。
3. 等待服务就绪，打开 `http://localhost:3000`。
4. 网页右上角“模型设置”填写自己的供应商、模型及 API Key；默认模型名称为 `deepseek-flash`，可用性以供应商为准。

无需预装 Node、JDK 或 Maven。启动器将校验后的固定版本工具放在 `.runtime`，不修改系统环境、不要求管理员权限。支持 Windows 10/11 x64；ARM64 有安装配置，尚未取得实机验收结果。

保持启动窗口打开；Ctrl+C 或关闭窗口会停止本次服务。前端热更新，Java 修改后需重启。默认端口 3000/8080 被占用时明确报错，不强制结束其他程序。

`启动农心-手机模式.bat` 允许同一可信局域网的手机访问网页；微信扫码与提醒管理仍仅供本机页面使用，不是微信网页登录。详见 [启动工具说明](tools/development/README.md)。

## 微信聊天与农事提醒

1. 先填写自己的模型 Key，点击“连接微信”，用本人微信扫码确认。
2. 在微信向农心发送一条消息，建立可发送消息的会话上下文。
3. 在网页从 AI 方案点击“加入任务”，登记后逐项弹出 **“确认农事安排与微信提醒”**。手动新建任务后也会进入该窗口。
4. 勾选“启用”，选择 **未来的北京时间**，确认允许发送该任务摘要，点击 **“确认安排并保存提醒”**。
5. 已有任务可通过列表“确认安排”或“微信提醒”直接设置；“编辑”窗口仍可修改提醒。

不启用或点“稍后安排”不会新增提醒。默认时间为任务日期当天 09:00；日期缺失或该时刻已过时，必须手动选未来时间，不自动补造日期。每个任务一个有效单次提醒，多项任务各自设置时间。

有自有 Key 时，启用提醒可调用一次 AI 生成待确认的时间建议（可能计费）；手动编辑后迟到建议不会覆盖输入。保存才生效，AI 不后台修改已保存时间。任务确认成功但提醒保存失败时，会明确提示并保留时间草稿，不冒充提醒已生效。

任务完成、取消或删除会取消未发送提醒；服务恢复后将仍有效的错过提醒合并补发。提醒绑定扫码账号，换账号不会转发旧任务；结果不确定时不盲目重发，人工重试可能重复。接口接受不等于用户已读。

电脑、Java 服务及微信连接需保持运行。关闭网页不会停止桥接；服务重启后要重新扫码并建立微信会话。微信通道使用第三方 `wechat-ilink-sdk 2.3.3`，不是自研微信协议。长时间延迟送达仍须真人账号验证。

## 项目结构

```text
frontend/                   独立前端工程、测试及正式品牌素材
  src/app/                  布局与功能组合
  src/features/             聊天、田块、任务、天气、设置、微信等
  src/shared/               公共 HTTP 客户端、UI 与工具
server/                     单模块 Spring Boot 工程
  src/main/java/com/nongxin/
    controller/ dto/        HTTP 入口和请求响应
    service/                业务接口，impl/ 子目录存放实现
    repository/             持久化接口，jdbc/ 子目录存放 SQL 与实现
    agent/ integration/     Agent 循环与模型、天气、微信适配
    domain/ security/       领域状态与归属、访问限制
    config/ bootstrap/ web/ 配置、迁移备份、SSE 和异常映射
  src/main/resources/       配置、SQL、城市表和必需知识索引
  src/test/                 隔离的自动化测试
tools/development/          启动、工具安装、校验、检查和源码分享
启动农心.bat                本机启动入口
启动农心-手机模式.bat       局域网前端启动入口
```

数据库、上传与备份位于 `server/data`；启动工具固定后端工作目录，不覆盖已有数据。没有 MyBatis，不混入另一个数据库框架。

## 开发与检查

使用私有工具，不依赖全局 PATH：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/development/dev.ps1 -Action Check
```

该命令执行后端测试与打包、前端源码/测试类型检查、lint、测试、构建及启动器检查。测试使用临时数据库与模拟模型，不读取真实田块库、不调用付费模型；真人扫码与专业农业准确性验证不能用自动化测试替代。

已有 Node、JDK 21、Maven 时，可分别在两个终端运行：

```sh
npm --prefix frontend ci
npm --prefix frontend run dev
```

```sh
cd server
mvn package
java -Dfile.encoding=UTF-8 -jar target/nongxin-agent.jar
```

手动构建前先停止旧 Java 进程，避免 Windows JAR 文件锁。环境配置示例见 `.env.example`；可另建 `.env.local`，不要上传真实配置。

## 数据与安全边界

- 自有 Key 自动明文保存在当前浏览器 localStorage；同浏览器、同网址重启可继续使用。无痕模式、清除网站数据或更换网址不会共享。公用设备请勿保存密钥。
- 模型 Key 不写入后端数据库或日志；微信令牌仅留运行内存，聊天记录落本机 SQLite。模型请求和微信消息仍经过外部服务，并非完全离线。
- 农技问答结合本地词法检索、来源约束图谱与可选向量检索；引用只接受本轮实际命中的来源，数值建议受依据核查约束。查不到充分依据应如实说明，不能声称专家确诊。
- 此版本不是完善的多用户公网产品，不要直接公网暴露 API。Java 默认监听本机，手机模式只用于可信局域网。
- 本仓库只发布程序源码、测试、README、启动工具及运行必需的品牌/城市/农技索引资源。**不包含 AI 记忆、历史报告、原文采集资料、赛题/PDF 附件、密钥、本机数据库、日志、IDE 配置、依赖或私有工具环境。**

生成同范围脱敏源码 ZIP：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/development/dev.ps1 -Action Share -ProgramOnly
```

输出到 `artifacts/releases`。该命令不会自动提交或推送 Git。

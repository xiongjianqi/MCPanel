# MCPanel

轻量级 Minecraft 服务端管理面板：单个 jar，浏览器里管理实例、终端、文件、备份和插件。

## 功能

| 模块 | 说明 |
| --- | --- |
| 实例管理 | 一个面板管理多个服务端实例，分别指定 Java、端口和启动命令 |
| 终端 | WebSocket 实时日志 + 命令发送，支持启动 / 关闭 / 重启 / 终止 |
| 仪表盘 | CPU / 内存 / 磁盘 / 进出流量，实时曲线与本日、本周历史曲线 |
| 文件管理 | 浏览、上传、下载、在线编辑、压缩/解压、批量操作 |
| 备份 | 一键备份与回滚 |
| Java 环境 | 安装并切换不同版本的 Java |
| 安装器 | Paper / 原版 / Fabric / Forge / NeoForge 一键安装；新建实例时也可以选 Velocity 代理 |
| 插件系统 | 面板插件 + 实例插件，自带事件总线和 Web 路由 |
| 用户与权限 | 主账号 + 子账号，按实例和功能授权，支持 2FA（TOTP / 邮箱验证码）与找回密码 |
| 邮件 | SMTP 配置与测试发送，用于登录验证、绑定邮箱、找回密码、注册邀请 |
| 其他 | 分享管理、远程管理（SSH / SFTP，需先绑定 2FA）、面板日志、配置管理 |

## 环境要求

- Java 11 及以上，编译需要 Maven 3
- 图形验证码用 `easy-captcha`，自带字体，启动时自动 `java.awt.headless=true`
- 线程数可用 `web.io.threads` / `web.worker.threads` 调整
- 日志读取、终端落盘这类阻塞型任务有硬上限 `panel.max.blocking.threads`（默认 256），
  超过会拒绝并打印提示，避免反复启停/插件高频调用把原生线程吃光
- 线程数超过 80 或每分钟增长很快时，面板日志会打印按名称聚合的线程统计（增长过快时附明细），
  `/api/panel/status` 也会返回 `threads`、`blocking_workers`、`blocking_worker_max`

## 快速开始

从 Releases 下载 `mc-panel.jar`，放进一个空目录后运行：

```bash
java -jar mc-panel.jar
```

首次启动会在当前目录生成配置、`users.json`、`users.key` 等，并在控制台打印初始管理员账号和密码。

## 自己编译

```bash
git clone https://github.com/xiongjianqi/MCPanel.git
cd MCPanel
mvn package
```

产物是 `target/mc-panel.jar`。

## 访问面板

浏览器打开 `http://localhost:8080`，局域网用本机 IP，公网直接访问或用端口映射。

## 插件开发

- `src/main/resources/web/plugin-dev-guide.txt`（面板插件）
- `src/main/resources/web/instance-plugin-dev-guide.txt`（实例插件）

## 开源协议

MIT License，见 [LICENSE](LICENSE)。

---- xiongjianqi

# MCPanel

一个轻量级的 Minecraft 服务端管理面板：打包成一个 jar，浏览器打开就能管实例、看终端、改文件、做备份、装插件。

- 语言：Java 11（后端）+ 原生 HTML / JavaScript（前端，不需要构建步骤）
- 存储：不依赖数据库，全部落在本地 JSON 文件里
- 部署：直接 `java -jar` 跑，也可以放进 Docker

## 功能

| 模块 | 说明 |
| --- | --- |
| 实例管理 | 一个面板管理多个服务端实例，分别指定 Java、端口和启动命令 |
| 终端 | WebSocket 实时日志 + 命令发送，支持启动 / 关闭 / 重启 / 终止，电脑端日志区高度自适应窗口 |
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

- Java 11 及以上（代码用到 `java.net.http`，编译目标 11）
- 编译需要 Maven 3
- 图形验证码用到 JDK 的 AWT（`java.desktop`），启动时会自动设置 `java.awt.headless=true`；验证码字体由 `easy-captcha` 自带，不用额外装系统字体

### 小内存 / 容器部署

- Undertow 默认 worker 线程数是「CPU×8」，面板还额外跑了一个分享服务，两者叠加在大核数机器上可能上百线程；本面板已收敛为 IO 4 + Worker 24（分享服务 2 + 8），可用 `web.io.threads` / `web.worker.threads` 覆盖
- 面板自身堆给 `-Xmx256M` ~ `-Xmx512M` 就够，实例内存单独在实例设置里配；只有 512MB 内存的机器建议：

  ```bash
  java -Xmx256M -Xss512k -jar mc-panel.jar
  ```
- 出现 `unable to create native thread: possibly out of memory or process/resource limits reached` 时，先查这几处：
  - `ulimit -u`（用户可创建进程/线程数）
  - systemd 服务的 `TasksMax`，或容器 `--pids-limit`
  - 容器/虚拟机的总内存：每个线程约 1MB 栈，线程多 + 堆大时容易先撞内存上限

## 快速开始

从 Releases 下载 `mc-panel.jar`，放进一个空目录后运行：

```bash
java -jar mc-panel.jar
```

首次启动会在当前目录生成配置文件、`users.json`、`users.key` 等，并在控制台打印初始管理员账号和密码，登录后请第一时间修改。

## 自己编译

```bash
git clone https://github.com/xiongjianqi/MCPanel.git
cd MCPanel
mvn package
```

产物是 `target/mc-panel.jar`，把它放进空目录再按上面的命令运行即可。

## 访问面板

- 本机：`http://localhost:8080`
- 局域网：`http://<局域网IP>:8080`
- 公网：公网 IP，或者用内网穿透把 8080 映射出去

## 插件开发

插件开发文档就在源码里：

- `src/main/resources/web/plugin-dev-guide.txt`（面板插件）
- `src/main/resources/web/instance-plugin-dev-guide.txt`（实例插件）

## 最近的改动

- **电脑端体验**：终端日志区按可视区域自适应，窗口缩放、切换页面、展开「终端设置」时都会重新计算；电脑端监控图表也加高了
- **找回密码**：只认账号**已绑定**的邮箱，页面只显示脱敏邮箱（如 `ab***@qq.com`），不再接受随手填的地址；主账号也支持邮箱找回
- **人机验证**：登录页「发送邮箱验证码」和找回页「发送验证码」都必须先通过图形验证码；改用 [`easy-captcha`](https://github.com/whvcse/EasyCaptcha) 库生成，一次性、5 分钟有效
- **监控曲线**：纵轴按数据自适应，空数据和单点不再被画成一条横贯全宽的平线

## 开发约定

- **能用成熟库就别手写**：比如图形验证码直接用 `easy-captcha`，不再自己拼图片/字体
- 新增依赖统一写进 `pom.xml`，构建时由 Maven 拉取，不要要求使用者手动放 jar
- 前端保持原生 JS + 无构建步骤，改完刷新页面即可

## 开源协议

MIT License，见 [LICENSE](LICENSE)。

---- xiongjianqi

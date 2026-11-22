package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.instance.InstanceRuntime;
import com.mcpanel.instance.InstanceRuntimeManager;
import com.mcpanel.util.LinuxUserManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class WebServer {
    private final ConfigManager globalConfig;
    private final InstanceRuntimeManager runtimeManager;
    private final AuthHandler auth;
    private final RateLimiter rateLimiter = new RateLimiter();
    private Undertow undertow;
    private Undertow shareUndertow;

    public WebServer(ConfigManager globalConfig, InstanceRuntimeManager runtimeManager) {
        this.globalConfig = globalConfig;
        this.runtimeManager = runtimeManager;
        this.auth = new AuthHandler(globalConfig, this.rateLimiter);
        LinuxUserManager.init();
    }

    public void start() {
        int port = globalConfig.getInt("web.port", 8080);
        int sharePort = globalConfig.getInt("share.port", 33333);

        SSLContext sslContext = null;
        boolean sslEnabled = globalConfig.getBool("web.ssl.enabled", false);
        if (sslEnabled) {
            String certPath = globalConfig.get("web.ssl.cert", "");
            String keyPath = globalConfig.get("web.ssl.key", "");
            if (!certPath.isEmpty() && !keyPath.isEmpty()) {
                try {
                    sslContext = com.mcpanel.util.SslUtil.createSslContext(
                            globalConfig.resolve(certPath), globalConfig.resolve(keyPath));
                } catch (Exception e) {
                    System.err.println("[Panel] SSL 证书加载失败: " + e.getMessage());
                }
            }
        }

        // 小内存/低配环境上线程数必须收敛：Undertow 默认 worker = CPU*8，两个服务叠加能到上百个线程，
        // 加上堆内存限制后极易出现 "unable to create native thread"。
        int cores = Runtime.getRuntime().availableProcessors();
        int ioThreads = globalConfig.getInt("web.io.threads", Math.max(2, Math.min(4, cores)));
        int workerThreads = globalConfig.getInt("web.worker.threads", Math.max(8, Math.min(16, cores * 2)));
        int shareWorkers = Math.max(4, Math.min(6, workerThreads / 2));
        System.out.println("[Panel] Web 线程配置: IO=" + ioThreads + ", Worker=" + workerThreads
                + ", 分享服务 Worker=" + shareWorkers + "（可用 web.io.threads / web.worker.threads 覆盖）");

        try {
            Undertow.Builder shareBuilder = Undertow.builder()
                    .setIoThreads(Math.min(2, ioThreads))
                    .setWorkerThreads(shareWorkers);
            if (sslContext != null) shareBuilder.addHttpsListener(sharePort, "0.0.0.0", sslContext);
            else shareBuilder.addHttpListener(sharePort, "0.0.0.0");
            shareUndertow = shareBuilder.setHandler(exchange -> new ShareHandler(globalConfig, auth).handle(exchange)).build();
            shareUndertow.start();
            System.out.println("[Panel] 分享服务已启动，端口: " + sharePort);
        } catch (Exception e) {
            System.err.println("[Panel] 分享服务启动失败: " + e.getMessage());
        }

        Undertow.Builder mainBuilder = Undertow.builder()
                .setIoThreads(ioThreads)
                .setWorkerThreads(workerThreads);
        // 显式放开请求体上限（部分 Undertow 版本默认会拒绝较大的请求体，
        // 表现为文件管理里上传稍大的 jar 直接失败）。业务层仍按 20GB 限制。
        try {
            mainBuilder.setServerOption(io.undertow.UndertowOptions.MAX_ENTITY_SIZE,
                    22L * 1024 * 1024 * 1024);
        } catch (Throwable ignored) {}
        if (sslContext != null) mainBuilder.addHttpsListener(port, "0.0.0.0", sslContext);
        else mainBuilder.addHttpListener(port, "0.0.0.0");

        undertow = mainBuilder.setHandler(exchange -> {
            if (exchange.isInIoThread()) {
                exchange.dispatch(this::handleRequest);
            } else {
                handleRequest(exchange);
            }
        }).build();

        undertow.start();
        System.out.println("[Panel] Web 管理界面已启动，端口: " + port);

        // 两个 Undertow 都起来之后再统计，数字才包含完整的线程池
        logThreadLimits();
    }

    // 启动时把线程概况合成一行打出来：本进程线程数、其他 java 进程线程数、进程/线程上限。
    // 出现 "unable to create native thread" 时这一行就够定位了，不再分三行输出。
    private static void logThreadLimits() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("[Panel] 线程: 本进程 ")
              .append(java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount())
              .append(" 条；").append(com.mcpanel.util.ProcStats.summary());
        } catch (Throwable ignored) {}
        try {
            java.nio.file.Path limits = java.nio.file.Paths.get("/proc/self/limits");
            if (java.nio.file.Files.exists(limits)) {
                for (String line : java.nio.file.Files.readAllLines(limits)) {
                    if (line.startsWith("Max processes")) {
                        sb.append("；").append(line.replaceAll("\\s+", " ").trim());
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.nio.file.Path pidsMax = java.nio.file.Paths.get("/sys/fs/cgroup/pids.max");
            if (java.nio.file.Files.exists(pidsMax)) {
                String v = new String(java.nio.file.Files.readAllBytes(pidsMax),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) sb.append("；cgroup pids.max ").append(v);
            }
        } catch (Throwable ignored) {}
        if (sb.length() > 0) System.out.println(sb);
    }

    private void handleRequest(HttpServerExchange exchange) {
        try {
            exchange.getResponseHeaders().put(new HttpString("X-Frame-Options"), "DENY");
            exchange.getResponseHeaders().put(new HttpString("X-Content-Type-Options"), "nosniff");

            String path = exchange.getRequestPath();
            String method = exchange.getRequestMethod().toString();
            boolean isUpgrade = "websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Upgrade"));

            if (!isUpgrade && !method.equals("GET") && !method.equals("OPTIONS")) {
                boolean isApiKeyCall = exchange.getRequestHeaders().getFirst("X-API-Key") != null;
                if (!isApiKeyCall) {
                    String origin = exchange.getRequestHeaders().getFirst("Origin");
                    String host = exchange.getRequestHeaders().getFirst("Host");
                    if (origin == null || !isOriginAllowed(origin, host)) {
                        exchange.setStatusCode(403);
                        exchange.getResponseSender().send("CSRF protection: Origin required");
                        return;
                    }
                }
            }

            // ★ 核心修复：强制退登后门 / 绕过验证，防止死锁
            if (path.equals("/api/account/logout") || path.equals("/?logout=1")) {
                auth.logout(exchange);
                if (path.equals("/?logout=1")) {
                    exchange.setStatusCode(302);
                    exchange.getResponseHeaders().put(Headers.LOCATION, "/login");
                    exchange.endExchange();
                } else {
                    exchange.setStatusCode(200);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send("{\"ok\":true}");
                }
                return;
            }

            if (path.equals("/api/health") && method.equals("GET")) {
                exchange.setStatusCode(200);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send("{\"ok\":true}");
                return;
            }

            if (path.startsWith("/plugins/")) {
                if (!auth.isAuthenticated(exchange)) {
                    exchange.setStatusCode(401);
                    exchange.getResponseSender().send("Unauthorized");
                    return;
                }
                if (com.mcpanel.plugin.PluginWebRouter.handle(exchange)) return;
            }

            if (path.startsWith("/api/auth/forgot/")) {
                if (path.equals("/api/auth/forgot/check") || path.equals("/api/auth/forgot/reset"))
                    auth.handleForgot(exchange, getClientIp(exchange));
                else auth.handleForgotEmail(exchange);
                return;
            }
            if (path.equals("/login") && method.equals("GET")) { auth.serveLoginPage(exchange); return; }
            if (path.equals("/register") && method.equals("GET")) { auth.serveRegisterPage(exchange); return; }
            if (path.equals("/forgot") && method.equals("GET")) { auth.serveForgotPage(exchange); return; }
            if (path.equals("/api/auth/captcha") && method.equals("GET")) { auth.handleCaptcha(exchange); return; }
            if (path.equals("/api/auth/setup-totp") && method.equals("POST")) { auth.handleSetupTotp(exchange); return; }
            if (path.equals("/api/auth/register") && method.equals("POST")) { auth.handleRegister(exchange); return; }
            if (path.equals("/api/auth/login/send-email") && method.equals("POST")) { auth.handleLoginSendEmail(exchange); return; }
            if (path.equals("/api/login") && method.equals("POST")) {
                String ip = getClientIp(exchange);
                if (rateLimiter.checkAndRecord(ip)) {
                    exchange.setStatusCode(429);
                    exchange.getResponseSender().send("Too Many Requests");
                    return;
                }
                auth.handleLogin(exchange, ip);
                return;
            }

            if (path.equals("/api/files/dl") && method.equals("GET")) {
                new FileHandler(globalConfig).serveDownload(exchange);
                return;
            }

            if (!auth.isAuthenticated(exchange)) {
                exchange.setStatusCode(302);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/login");
                exchange.endExchange();
                return;
            }

            String currentUser = auth.getCurrentUser(exchange);

            if (path.equals("/ws/panel-log")) {
                // 侧边栏对 panel_log 或 config 权限都会显示入口，这里保持一致
                if (!UserStore.getInstance().isMaster(currentUser)
                        && !UserStore.getInstance().hasPermission(currentUser, "config")
                        && !UserStore.getInstance().hasPermission(currentUser, "panel_log")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("Forbidden");
                    return;
                }
                PanelLogManager.handleUpgrade(exchange);
                return;
            }

            if (path.startsWith("/api/system/")) {
                if (path.equals("/api/system/upload-default-bg")
                        && !UserStore.getInstance().isMaster(currentUser)) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"need master\"}");
                    return;
                }
                new SystemHandler(globalConfig).handle(exchange);
                return;
            }

            String instanceId = readInstanceId(exchange);
            InstanceRuntime rt = (instanceId == null || instanceId.isEmpty()) ? null : runtimeManager.get(instanceId);

            if (rt != null && !UserStore.getInstance().canAccessInstance(currentUser, instanceId)) {
                exchange.getResponseHeaders().put(Headers.SET_COOKIE,
                        "instance=; Path=/; SameSite=Lax; Max-Age=0");
                rt = null;
            }

            if (path.equals("/ws/terminal")) {
                if (!auth.hasPermission(exchange, "terminal")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("Forbidden: no terminal permission");
                    return;
                }
                if (rt == null) {
                    exchange.setStatusCode(400);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    exchange.getResponseSender().send(
                            "{\"error\":\"instance runtime not ready, please restart panel\"}");
                    return;
                }
                rt.terminalHandler.handleUpgrade(exchange, currentUser);
                return;
            }

            if (path.startsWith("/api/panel/plugins")) {
                if (!auth.hasPermission(exchange, "plugins")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"no permission\"}");
                    return;
                }
                new PluginHandler(com.mcpanel.plugin.PanelPluginManager.getInstance().getManager(),
                        globalConfig, auth).handle(exchange);
                return;
            }
            if (path.startsWith("/api/terminal/") || path.startsWith("/api/panel/")) {
                if (rt == null) {
                    exchange.setStatusCode(400);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    exchange.getResponseSender().send(
                            "{\"error\":\"instance runtime not ready, please restart panel\"}");
                    return;
                }
                if (path.startsWith("/api/panel/") && !auth.hasPermission(exchange, "terminal")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                    exchange.getResponseSender().send("{\"error\":\"no permission: terminal\"}");
                    return;
                }
                rt.terminalHandler.handleApi(exchange, currentUser);
                return;
            }

            if (path.equals("/ws/plugin-events")) { new com.mcpanel.plugin.PluginEventSocketHandler(auth).handle(exchange); return; }
            if (path.startsWith("/api/plugin-pages")) { new PluginPageHandler(auth).handle(exchange); return; }
            if (path.startsWith("/api/plugin-api/")) { new PluginApiHandler(auth).handle(exchange); return; }


            if (path.startsWith("/api/instances")) { new InstanceHandler(runtimeManager, auth).handle(exchange); return; }
            if (path.startsWith("/api/remote")) { new RemoteHandler(globalConfig, runtimeManager, auth).handle(exchange); return; }
            if (path.startsWith("/api/userdata")) { new UserDataHandler(globalConfig, auth).handle(exchange); return; }
            if (path.startsWith("/api/account")) { new AccountHandler(globalConfig, auth).handle(exchange); return; }

            if (path.startsWith("/api/users")) {
                if (!auth.hasPermission(exchange, "users")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"no permission: users\"}");
                    return;
                }
                new UserHandler(globalConfig, auth).handle(exchange);
                return;
            }

            if (path.startsWith("/api/smtp/")) {
                if (!UserStore.getInstance().isMaster(currentUser)) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"need master\"}");
                    return;
                }
                new SmtpHandler(globalConfig, auth).handle(exchange);
                return;
            }

            if (path.startsWith("/api/config")) {
                if (!auth.hasPermission(exchange, "config")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"no permission: config\"}");
                    return;
                }
                // ★ 只认显式的 ?instance= 参数，不再回退到 instance cookie：
                // 以前进过实例后 cookie 一直留着，打开全局「配置管理」时会被当成实例配置，
                // 自签 SSL 因此被写进 instances/<id>/ssl，面板启动时读全局配置当然不生效。
                InstanceRuntime cfgRt = configTargetFromQuery(exchange);
                // SSL 证书是面板级配置，永远写全局，不接受实例上下文
                boolean panelSsl = path.equals("/api/config/generate-ssl") || path.equals("/api/config/upload-ssl");
                ConfigManager cm = (cfgRt != null && !panelSsl) ? cfgRt.config : globalConfig;
                new ConfigHandler(cm, auth, cfgRt != null && !panelSsl).handle(exchange);
                return;
            }

            if (path.startsWith("/api/share")) {
                if (!auth.hasPermission(exchange, "share")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"no permission: share\"}");
                    return;
                }
                new ShareHandler(globalConfig, auth).handle(exchange);
                return;
            }

            if (path.startsWith("/api/java")) {
                if (!auth.hasPermission(exchange, "java") && !auth.hasPermission(exchange, "config")) {
                    exchange.setStatusCode(403);
                    exchange.getResponseSender().send("{\"error\":\"no permission: java\"}");
                    return;
                }
                ConfigManager javaCfg = (rt != null) ? rt.config : globalConfig;
                new JavaHandler(javaCfg, auth, currentUser).handle(exchange);
                return;
            }

            if (path.startsWith("/api/files")) {
                String iid = readInstanceId(exchange);
                boolean hasAll = auth.hasPermission(exchange, "files_all");
                boolean hasFiles = auth.hasPermission(exchange, "files");

                Path sandbox;
                ConfigManager cm;
                List<String> allowedSubdirs = null;

                if (iid != null && !iid.isEmpty()) {
                    if (!hasAll && !hasFiles) {
                        exchange.setStatusCode(403); exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        exchange.getResponseSender().send("{\"error\":\"no permission: files\"}"); return;
                    }
                    sandbox = Paths.get("instances", iid).toAbsolutePath().normalize();
                    if (!sandbox.toFile().exists()) sandbox.toFile().mkdirs();
                    cm = rt != null ? rt.config : globalConfig;
                } else {
                    if (hasAll) {
                        sandbox = Paths.get(".").toAbsolutePath().normalize();
                        cm = globalConfig;
                    } else {
                        exchange.setStatusCode(403); exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        exchange.getResponseSender().send("{\"error\":\"全局文件管理需要完全文件权限 (files_all)\"}"); return;
                    }
                }

                Set<String> userPerms = new HashSet<>(UserStore.getInstance().getPermissions(currentUser));
                new FileHandler(cm, sandbox, allowedSubdirs, userPerms).handle(exchange);
                return;
            }

            if (rt == null) {
                if (path.startsWith("/api/")) {
                    exchange.setStatusCode(400);
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send("{\"error\":\"no instance selected\"}");
                    return;
                }
                new FileHandler(globalConfig).serveStatic(exchange, path);
                return;
            }

            if (path.startsWith("/api/")) {
                routeApi(exchange, path, rt, currentUser);
                return;
            }
            new FileHandler(rt.config).serveStatic(exchange, path);

        } catch (Throwable t) {
            System.err.println("[Web] 未捕获异常: " + t);
            t.printStackTrace();
            if (!exchange.isResponseStarted()) {
                exchange.setStatusCode(500);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
                exchange.getResponseSender().send("{\"error\":\"internal server error\"}");
            } else {
                exchange.endExchange();
            }
        }
    }

    private void routeApi(HttpServerExchange exchange, String path, InstanceRuntime rt, String currentUser) {
        String need = permForPath(path);
        if (need != null && !UserStore.getInstance().hasInstancePermission(currentUser, rt.inst.id, need)) {
            exchange.setStatusCode(403);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"no permission: " + need + "\"}");
            return;
        }
        if (path.startsWith("/api/dashboard"))
            new DashboardHandler(rt.mcServer, rt.config).handle(exchange);
        else if (path.startsWith("/api/terminal") || path.startsWith("/api/panel"))
            rt.terminalHandler.handleApi(exchange, currentUser);
        else if (path.startsWith("/api/backup"))
            new BackupHandler(rt.config, rt.mcServer, rt.terminalHandler, rt.rollbackService).handle(exchange);
        else if (path.startsWith("/api/plugins"))
            new PluginHandler(rt.pluginManager, rt.config, auth).handle(exchange);
        else if (path.startsWith("/api/serverprops"))
            new ServerPropsHandler(rt.config).handle(exchange);
        else if (path.startsWith("/api/installer"))
            new InstallerHandler(rt).handle(exchange);
        else {
            exchange.setStatusCode(404);
            exchange.getResponseSender().send("{\"error\":\"not found\"}");
        }
    }

    private String permForPath(String path) {
        if (path.startsWith("/api/dashboard")) return null;
        if (path.startsWith("/api/terminal")) return "terminal";
        if (path.startsWith("/api/panel")) return "terminal";
        if (path.startsWith("/api/backup")) return "backup";
        if (path.startsWith("/api/plugins")) return "plugins";
        if (path.startsWith("/api/serverprops")) return "config";
        if (path.startsWith("/api/installer")) return "config";
        return null;
    }

    /** 只从显式 ?instance= 解析实例（用于配置页这类“默认全局”的接口） */
    private InstanceRuntime configTargetFromQuery(HttpServerExchange exchange) {
        Deque<String> qp = exchange.getQueryParameters().get("instance");
        if (qp == null || qp.isEmpty()) return null;
        String id = qp.peekFirst();
        if (id == null || id.isEmpty()) return null;
        return runtimeManager.get(id);
    }

    private String readInstanceId(HttpServerExchange exchange) {
        Deque<String> qp = exchange.getQueryParameters().get("instance");
        if (qp != null && !qp.isEmpty()) {
            String id = qp.peekFirst();
            if (id != null && !id.isEmpty()) return id;
        }
        return readCookie(exchange, "instance");
    }

    private String readCookie(HttpServerExchange ex, String name) {
        String cookie = ex.getRequestHeaders().getFirst(Headers.COOKIE);
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) {
                String val = part.substring(name.length() + 1);
                try {
                    return java.net.URLDecoder.decode(val.replace("\"", "").trim(), "UTF-8");
                } catch (Exception e) {
                    return val;
                }
            }
        }
        return null;
    }

    private String getClientIp(HttpServerExchange exchange) {
        boolean isTrusted = false;
        try {
            if (exchange.getSourceAddress() != null && exchange.getSourceAddress().getAddress() != null) {
                isTrusted = exchange.getSourceAddress().getAddress().isLoopbackAddress()
                        || exchange.getSourceAddress().getAddress().isSiteLocalAddress();
            }
        } catch (Exception ignored) {}
        if (isTrusted) {
            String h = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (h != null && !h.isEmpty()) {
                int comma = h.indexOf(',');
                return comma > 0 ? h.substring(0, comma).trim() : h.trim();
            }
            String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (real != null && !real.isEmpty()) return real;
        }
        if (exchange.getSourceAddress() != null && exchange.getSourceAddress().getAddress() != null) {
            return exchange.getSourceAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private boolean isOriginAllowed(String origin, String host) {
        if (origin == null || host == null) return false;
        try {
            java.net.URI u = java.net.URI.create(origin);
            String scheme = u.getScheme();
            String originHost = u.getHost();
            if (scheme == null || originHost == null) return false;
            int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            int originPort = (u.getPort() == -1) ? defaultPort : u.getPort();

            String h = host.trim();
            String hostOnly;
            int hostPort;
            if (h.startsWith("[")) {
                int end = h.indexOf(']');
                if (end < 0) return false;
                hostOnly = h.substring(0, end + 1);
                String rest = h.substring(end + 1).trim();
                if (rest.startsWith(":")) hostPort = parsePort(rest.substring(1), defaultPort);
                else if (rest.isEmpty()) hostPort = defaultPort;
                else return false;
            } else {
                int firstColon = h.indexOf(':');
                int lastColon = h.lastIndexOf(':');
                if (firstColon > 0 && firstColon == lastColon) {
                    hostOnly = h.substring(0, firstColon);
                    hostPort = parsePort(h.substring(firstColon + 1), defaultPort);
                } else {
                    hostOnly = h;
                    hostPort = defaultPort;
                }
            }
            if (hostPort < 0) return false;
            return normalizeHost(hostOnly).equalsIgnoreCase(normalizeHost(originHost))
                    && hostPort == originPort;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeHost(String h) {
        if (h == null) return "";
        h = h.trim();
        if (h.startsWith("[")) h = h.substring(1);
        if (h.endsWith("]")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static int parsePort(String p, int def) {
        try {
            String v = p.trim();
            if (v.isEmpty()) return def;
            int n = Integer.parseInt(v);
            return (n > 0 && n <= 65535) ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
package com.mcpanel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.plugin.PluginManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PluginHandler {
    private final PluginManager pluginManager;
    private final ConfigManager config;
    private final AuthHandler auth;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_UPLOAD_SIZE = 50 * 1024 * 1024;

    public PluginHandler(PluginManager pluginManager, ConfigManager config, AuthHandler auth) {
        this.pluginManager = pluginManager;
        this.config = config;
        this.auth = auth;
    }

    public void handle(HttpServerExchange exchange) {
        String user = auth.getCurrentUser(exchange);
        if (!UserStore.getInstance().isMaster(user) && !auth.hasPermission(exchange, "plugins")) {
            exchange.setStatusCode(403);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"no permission: plugins\"}");
            return;
        }

        String rawPath = exchange.getRequestPath();
        String path = rawPath.startsWith("/api/panel/plugins")
                ? "/api/plugins" + rawPath.substring("/api/panel/plugins".length())
                : rawPath;
        String method = exchange.getRequestMethod().toString();

        if (path.equals("/api/plugins/upload") && method.equals("POST")) {
            if (!UserStore.getInstance().isMaster(user)) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("{\"error\":\"只有所有者可以上传插件\"}");
                return;
            }
            handleUpload(exchange);
            return;
        }

        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    Map<String, Object> result = new HashMap<>();

                    if (path.equals("/api/plugins/list")) {
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(pluginManager.listPlugins()));
                        return;
                    }

                    Map<String, String> p = new HashMap<>();
                    if (body != null && !body.isEmpty()) {
                        p = MAPPER.readValue(body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                    }

                    if (path.equals("/api/plugins/enable") || path.equals("/api/plugins/disable") || path.equals("/api/plugins/unload")) {
                        if (!UserStore.getInstance().isMaster(user)) {
                            ex.setStatusCode(403);
                            ex.getResponseSender().send("{\"error\":\"只有所有者可以操作插件\"}");
                            return;
                        }
                    }
                    String jarName = p.get("jarName");
                    if (jarName != null && !jarName.matches("^[a-zA-Z0-9_\\-]+\\.jar$")) {
                        ex.setStatusCode(400);
                        ex.getResponseSender().send("{\"error\":\"非法插件文件名\"}");
                        return;
                    }

                    if (path.equals("/api/plugins/enable")) {
                        pluginManager.enablePlugin(jarName);
                        result.put("ok", true);
                    } else if (path.equals("/api/plugins/disable")) {
                        pluginManager.disablePlugin(jarName);
                        result.put("ok", true);
                    } else if (path.equals("/api/plugins/unload")) {
                        pluginManager.unloadPlugin(jarName);
                        result.put("ok", true);
                    } else if (path.equals("/api/plugins/load")) {
                        // ★ 核心修复：加载已有 jar 也是代码执行，必须 master
                        if (!UserStore.getInstance().isMaster(user)) {
                            ex.setStatusCode(403);
                            ex.getResponseSender().send("{\"error\":\"只有所有者可以加载插件\"}");
                            return;
                        }
                        File f = config.resolve("plugins/" + jarName);
                        result.put("ok", pluginManager.loadPlugin(f, true));
                    } else if (path.equals("/api/plugins/delete")) {
                        if (!UserStore.getInstance().isMaster(user)) {
                            ex.setStatusCode(403);
                            ex.getResponseSender().send("{\"error\":\"只有所有者可以删除插件\"}");
                            return;
                        }
                        result.put("ok", pluginManager.deletePlugin(jarName));
                    } else {
                        ex.setStatusCode(404);
                        ex.getResponseSender().send("{\"error\":\"unknown\"}");
                        return;
                    }

                    ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                    ex.getResponseSender().send(MAPPER.writeValueAsString(result));
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"内部错误\"}");
                }
            });
        });
    }

    private void handleUpload(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> handleUpload(exchange));
            return;
        }
        try {
            Deque<String> names = exchange.getQueryParameters().get("name");
            if (names == null || names.isEmpty()) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"missing name\"}");
                return;
            }
            String name = names.peekFirst();
            if (!name.endsWith(".jar") || !name.matches("^[a-zA-Z0-9_\\-]+\\.jar$")) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"非法文件名\"}");
                return;
            }

            File dirFile = config.resolve("plugins");
            Path dir = dirFile.toPath();
            Files.createDirectories(dir);
            Path dst = dir.resolve(name);

            if (Files.exists(dst)) {
                exchange.setStatusCode(400);
                exchange.getResponseSender().send("{\"error\":\"同名插件已存在，请先删除\"}");
                return;
            }

            String contentLength = exchange.getRequestHeaders().getFirst(Headers.CONTENT_LENGTH);
            if (contentLength != null) {
                try {
                    long size = Long.parseLong(contentLength);
                    if (size > MAX_UPLOAD_SIZE) {
                        exchange.setStatusCode(413);
                        exchange.getResponseSender().send("{\"error\":\"插件文件过大 (限 50MB)\"}");
                        return;
                    }
                } catch (Exception ignored) {}
            }

            exchange.startBlocking();
            long totalRead = 0;
            try (InputStream in = exchange.getInputStream();
                 OutputStream out = Files.newOutputStream(dst,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    totalRead += n;
                    if (totalRead > MAX_UPLOAD_SIZE) {
                        out.close();
                        Files.deleteIfExists(dst);
                        exchange.setStatusCode(413);
                        exchange.getResponseSender().send("{\"error\":\"插件文件过大 (限 50MB)\"}");
                        return;
                    }
                    out.write(buf, 0, n);
                }
            }
            pluginManager.loadPlugin(dst.toFile(), true);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"ok\":true}");
        } catch (Exception e) {
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"上传失败\"}");
        }
    }
}

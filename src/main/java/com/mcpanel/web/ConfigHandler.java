package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.util.SshManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public class ConfigHandler {
    private final ConfigManager config;
    private final AuthHandler auth;
    private final boolean instanceMode;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ConfigHandler(ConfigManager config, AuthHandler auth, boolean instanceMode) {
        this.config = config;
        this.auth = auth;
        this.instanceMode = instanceMode;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();

        if (path.equals("/api/config/get")) {
            try {
                String user = auth.getCurrentUser(exchange);
                boolean isMaster = UserStore.getInstance().isMaster(user);
                Map<String, String> all = config.getAll();
                
                // 彻底清理敏感配置和已迁移的配置
                all.keySet().removeIf(k -> k.startsWith("smtp.") || k.equals("panel.host"));

                if (!isMaster) {
                    all.keySet().removeIf(k -> k.startsWith("security.") || k.contains("start.command") || k.contains("image") || k.contains("ssl."));
                }

                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(MAPPER.writeValueAsString(all));
            } catch (Exception e) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"内部错误\"}");
            }
            return;
        }

        if (path.equals("/api/config/set")) {
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> {
                    try {
                        String user = auth.getCurrentUser(ex);
                        Map<String, String> params = MAPPER.readValue(body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                    
                        boolean isMaster = UserStore.getInstance().isMaster(user);
                        for (String k : params.keySet()) {
                            if (k.startsWith("smtp.")) {
                                ex.setStatusCode(403);
                                ex.getResponseSender().send("{\"error\":\"SMTP 配置已受保护，请前往 SMTP 管理页面修改\"}");
                                return;
                            }
                            
                            if (!isMaster) {
                                if (k.startsWith("security.") || k.startsWith("panel.") || k.startsWith("remote.") ||
                                    k.contains("start.command") || k.contains("server.dir") || k.contains("sandbox.mode") ||
                                    k.contains("image") || k.contains("ssl.") || k.contains("password") || k.contains("secret") || k.equals("mc.java.path")) {
                                    ex.setStatusCode(403);
                                    ex.getResponseSender().send("{\"error\":\"只有所有者可以修改核心安全配置\"}");
                                    return;
                                }
                            }
                            if (instanceMode) {
                                if (k.startsWith("web.") || k.startsWith("security.") || k.equals("share.port") || k.startsWith("node.") || k.startsWith("remote.")) {
                                    ex.setStatusCode(403);
                                    ex.getResponseSender().send("{\"error\":\"实例模式下不允许修改全局配置项: " + k + "\"}");
                                    return;
                                }
                            }
                        }

                        for (Map.Entry<String, String> e : params.entrySet()) {
                            config.set(e.getKey(), e.getValue());
                        }

                        if (params.containsKey("remote.ssh.port")) {
                            com.mcpanel.util.Async.run(() -> SshManager.initSsh(System.out::println));
                        }
                    
                        ex.getResponseSender().send("{\"ok\":true}");
                    } catch (Exception e) {
                        ex.setStatusCode(500);
                        ex.getResponseSender().send("{\"error\":\"内部错误\"}");
                    }
                });
            });
            return;
        }

        if (path.equals("/api/config/generate-ssl")) {
            if (!UserStore.getInstance().isMaster(auth.getCurrentUser(exchange))) {
                exchange.setStatusCode(403); exchange.getResponseSender().send("{\"error\":\"need master\"}"); return;
            }
            try {
                File sslDir = config.resolve("ssl");
                if (!sslDir.exists()) sslDir.mkdirs();
                File certFile = new File(sslDir, "cert.pem");
                File keyFile = new File(sslDir, "key.pem");
                com.mcpanel.util.SslUtil.generateSelfSigned(certFile, keyFile);
                config.set("web.ssl.enabled", "true");
                config.set("web.ssl.cert", "ssl/cert.pem");
                config.set("web.ssl.key", "ssl/key.pem");
                exchange.getResponseSender().send("{\"ok\":true}");
            } catch (Exception e) {
                exchange.setStatusCode(500);
                exchange.getResponseSender().send("{\"error\":\"生成失败，请查看日志\"}");
            }
            return;
        }

        if (path.equals("/api/config/upload-ssl")) {
            if (!UserStore.getInstance().isMaster(auth.getCurrentUser(exchange))) {
                exchange.setStatusCode(403); exchange.getResponseSender().send("{\"error\":\"need master\"}"); return;
            }
            exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                ex.dispatch(() -> {
                    try {
                        Map<String, String> params = MAPPER.readValue(body,
                                MAPPER.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                        String cert = params.get("cert");
                        String key = params.get("key");
                    
                        if (cert == null || cert.isEmpty() || key == null || key.isEmpty() || cert.length() > 50000 || key.length() > 50000) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"证书和私钥不能为空\"}"); return;
                        }
                    
                        if (!cert.contains("BEGIN CERTIFICATE") || !key.contains("PRIVATE KEY")) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"证书或私钥格式错误\"}"); return;
                        }
                        File sslDir = config.resolve("ssl");
                        if (!sslDir.exists()) sslDir.mkdirs();
                    
                        File certFile = new File(sslDir, "cert.pem");
                        File keyFile = new File(sslDir, "key.pem");
                    
                        Files.write(certFile.toPath(), cert.getBytes(StandardCharsets.UTF_8));
                        Files.write(keyFile.toPath(), key.getBytes(StandardCharsets.UTF_8));
                        try {
                            certFile.setReadable(false, false); certFile.setReadable(true, true);
                            keyFile.setReadable(false, false); keyFile.setReadable(true, true);
                            keyFile.setWritable(false, false); keyFile.setWritable(true, true);
                        } catch (Exception ignored) {}
                    
                        config.set("web.ssl.enabled", "true");
                        config.set("web.ssl.cert", "ssl/cert.pem");
                        config.set("web.ssl.key", "ssl/key.pem");
                    
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send("{\"ok\":true}");
                    } catch (Exception e) {
                        ex.setStatusCode(500); ex.getResponseSender().send("{\"error\":\"内部错误\"}");
                    }
                });
            });
            return;
        }

        exchange.setStatusCode(404); exchange.getResponseSender().send("{\"error\":\"unknown\"}");
    }
}
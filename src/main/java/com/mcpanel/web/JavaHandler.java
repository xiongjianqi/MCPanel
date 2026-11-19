package com.mcpanel.web;

import com.mcpanel.config.ConfigManager;
import com.mcpanel.config.UserStore;
import com.mcpanel.mc.JavaManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.*;

public class JavaHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConfigManager config;
    private final AuthHandler auth;
    private final String currentUser;

    public JavaHandler(ConfigManager config, AuthHandler auth, String currentUser) {
        this.config = config;
        this.auth = auth;
        this.currentUser = currentUser;
    }

    public void handle(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            // ★ 修复：用 exchange.dispatch 把回调体切到 Worker 线程执行。
            // 不能只丢给普通线程池异步执行：receiver 回调返回后 Undertow 会结束 exchange，
            // 之后再 setStatus/send 会抛 "response has already been started"，登录会直接失效。
            ex.dispatch(() -> {
                try {
                    if (path.equals("/api/java/list")) {
                        List<Map<String, Object>> list = new ArrayList<>();
                        for (String type : JavaManager.JAVA_TYPES) {
                            for (int v : JavaManager.JAVA_VERSIONS) {
                                Map<String, Object> m = new HashMap<>();
                                m.put("type", type);
                                m.put("version", v);
                                boolean inst = JavaManager.isInstalled(type, v);
                                m.put("installed", inst);
                                m.put("downloading", JavaManager.isDownloading(type, v));
                            
                                JavaManager.DownloadStatus st = JavaManager.getStatus(type, v);
                                if (st != null) {
                                    m.put("progress", st.progress);
                                    m.put("state", st.state);
                                    m.put("totalBytes", st.totalBytes);
                                    m.put("downloadedBytes", st.downloadedBytes);
                                } else {
                                    m.put("state", inst ? "success" : "idle");
                                }
                                list.add(m);
                            }
                        }
                        Map<String, Object> res = new HashMap<>();
                        res.put("list", list);
                        res.put("current", config.get("mc.java.path", ""));
                        ex.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                        ex.getResponseSender().send(MAPPER.writeValueAsString(res));
                        return;
                    }

                    Map<String, String> p = MAPPER.readValue(body == null ? "{}" : body, Map.class);
                    String type = p.get("type");
                    String versionStr = p.get("version");

                    boolean isInstanceCfg = config.get("_instance.id", "") != null && !config.get("_instance.id", "").isEmpty();
                    if (!isInstanceCfg && !UserStore.getInstance().isMaster(currentUser)) {
                        ex.setStatusCode(403);
                        ex.getResponseSender().send("{\"error\":\"只有所有者可以修改全局 Java 环境\"}");
                        return;
                    }

                    if (path.equals("/api/java/set")) {
                        String ver = p.get("version");
                        if (ver == null || ver.isEmpty()) {
                            ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"missing version\"}"); return;
                        }
                        if ("default".equals(ver)) {
                            config.remove("mc.java.path");
                        } else {
                            if (!"jre".equals(type) && !"jdk".equals(type)) {
                                ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"invalid type\"}"); return;
                            }
                            if (!ver.matches("\\d+")) {
                                ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"invalid version\"}"); return;
                            }
                            config.set("mc.java.path", JavaManager.getJavaBin(type, Integer.parseInt(ver)).getAbsolutePath());
                        }
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (!"jre".equals(type) && !"jdk".equals(type)) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"invalid type\"}"); return;
                    }
                    // ★ 修复：version 缺省/非法时 Integer.parseInt 会抛异常返回 500，这里统一按 400 处理
                    if (versionStr == null || !versionStr.matches("\\d{1,3}")) {
                        ex.setStatusCode(400); ex.getResponseSender().send("{\"error\":\"invalid version\"}"); return;
                    }
                    int version = Integer.parseInt(versionStr);

                    if (path.equals("/api/java/download")) {
                        JavaManager.download(type, version);
                        ex.getResponseSender().send("{\"ok\":true}");
                        return;
                    }

                    if (path.equals("/api/java/delete")) {
                        boolean ok = JavaManager.delete(type, version);
                        String currentPath = config.get("mc.java.path", "");
                        if (currentPath.contains(type + "-" + version)) {
                            config.remove("mc.java.path");
                        }
                        ex.getResponseSender().send("{\"ok\":" + ok + "}");
                        return;
                    }

                    ex.setStatusCode(404);
                    ex.getResponseSender().send("{\"error\":\"unknown\"}");
                } catch (Exception e) {
                    ex.setStatusCode(500);
                    ex.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
                }
            });
        });
    }
}
